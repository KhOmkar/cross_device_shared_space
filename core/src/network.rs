use std::sync::Arc;
use std::collections::HashMap;
use std::str::FromStr;
use tokio::sync::Mutex;
use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};
use iroh::endpoint::{Connection, Incoming, presets};
use iroh::{Endpoint, EndpointAddr, SecretKey, PublicKey, Watcher};
use crate::protocol::{ProtocolMessage, TrayItem, TrayDelete};
use crate::storage::Database;

// ALPN definition
pub const ALPN: &[u8] = b"cross-device-shared-space/v1";

pub trait NodeCallback: Send + Sync {
    fn on_tray_item_received(&self, item: TrayItem);
    fn on_tray_item_deleted(&self, item_id: String);
    fn on_peer_connected(&self, device_id: String, name: String);
    fn on_peer_disconnected(&self, device_id: String);
}

pub struct ConnectionState {
    pub connection: Connection,
    pub device_name: String,
    pub writer_tx: tokio::sync::mpsc::Sender<ProtocolMessage>,
}

pub struct NodeInner {
    pub db: Database,
    pub endpoint: Option<Endpoint>,
    pub secret_key: SecretKey,
    pub peers: HashMap<PublicKey, ConnectionState>,
    pub device_name: String,
    pub callback: Option<Arc<dyn NodeCallback>>,
    pub is_pairing_mode: bool,
}

impl NodeInner {
    pub fn new(db_path: &str, device_name: String, callback: Option<Arc<dyn NodeCallback>>) -> anyhow::Result<Self> {
        let db = Database::open(db_path)?;
        
        let secret_key = match db.get_config("secret_key")? {
            Some(hex_str) => {
                let bytes = hex::decode(hex_str)?;
                let byte_array: [u8; 32] = bytes.try_into().map_err(|_| anyhow::anyhow!("invalid secret key size"))?;
                SecretKey::from_bytes(&byte_array)
            }
            None => {
                let key = SecretKey::generate();
                db.set_config("secret_key", &hex::encode(key.to_bytes()))?;
                key
            }
        };

        Ok(Self {
            db,
            endpoint: None,
            secret_key,
            peers: HashMap::new(),
            device_name,
            callback,
            is_pairing_mode: false,
        })
    }

    pub fn get_my_node_id(&self) -> String {
        self.secret_key.public().to_string()
    }

    pub fn get_pairing_ticket(&self) -> anyhow::Result<String> {
        let endpoint = self.endpoint.as_ref().ok_or_else(|| anyhow::anyhow!("Node not started"))?;
        let endpoint_addr = endpoint.watch_addr().get();
        let ticket_json = serde_json::to_string(&endpoint_addr)?;
        Ok(ticket_json)
    }

    pub fn set_pairing_mode(&mut self, active: bool) {
        self.is_pairing_mode = active;
    }
}

// Send helper
pub async fn send_msg<W: tokio::io::AsyncWrite + Unpin>(writer: &mut W, msg: &ProtocolMessage) -> anyhow::Result<()> {
    let mut json = serde_json::to_vec(msg)?;
    json.push(b'\n');
    writer.write_all(&json).await?;
    writer.flush().await?;
    Ok(())
}

// Receive helper
pub async fn recv_msg<R: tokio::io::AsyncRead + Unpin>(reader: &mut BufReader<R>) -> anyhow::Result<ProtocolMessage> {
    let mut line = String::new();
    let bytes_read = reader.read_line(&mut line).await?;
    if bytes_read == 0 {
        return Err(anyhow::anyhow!("Connection closed"));
    }
    let msg: ProtocolMessage = serde_json::from_str(&line)?;
    Ok(msg)
}

// Node actions implementation
pub async fn start_node(inner_arc: Arc<Mutex<NodeInner>>) -> anyhow::Result<()> {
    let (secret_key, _device_name) = {
        let inner = inner_arc.lock().await;
        (inner.secret_key.clone(), inner.device_name.clone())
    };

    // Bind the endpoint
    let endpoint = Endpoint::builder(presets::N0)
        .secret_key(secret_key)
        .alpns(vec![ALPN.to_vec()])
        .bind()
        .await?;

    {
        let mut inner = inner_arc.lock().await;
        inner.endpoint = Some(endpoint.clone());
    }

    // Spawn accept loop
    let inner_clone = inner_arc.clone();
    let endpoint_clone = endpoint.clone();
    tokio::spawn(async move {
        while let Some(incoming) = endpoint_clone.accept().await {
            let inner = inner_clone.clone();
            tokio::spawn(async move {
                if let Err(e) = handle_incoming_connection(inner, incoming).await {
                    eprintln!("Error handling incoming connection: {:?}", e);
                }
            });
        }
    });

    // Spawn auto-reconnect manager
    let inner_clone = inner_arc.clone();
    tokio::spawn(async move {
        loop {
            tokio::time::sleep(tokio::time::Duration::from_secs(10)).await;
            let paired_devs = {
                let inner = inner_clone.lock().await;
                match inner.db.get_paired_devices() {
                    Ok(devs) => devs,
                    Err(e) => {
                        eprintln!("Failed to read paired devices: {:?}", e);
                        continue;
                    }
                }
            };

            for (device_id, public_key_str, _device_name) in paired_devs {
                let peer_pubkey = match PublicKey::from_str(&public_key_str) {
                    Ok(pk) => pk,
                    Err(_) => continue,
                };

                let already_connected = {
                    let inner = inner_clone.lock().await;
                    inner.peers.contains_key(&peer_pubkey)
                };

                if !already_connected {
                    // Try to reconnect in background
                    let inner = inner_clone.clone();
                    tokio::spawn(async move {
                        let endpoint = {
                            let locked = inner.lock().await;
                            locked.endpoint.clone()
                        };

                        if let Some(endpoint) = endpoint {
                            // Construct EndpointAddr
                            let endpoint_addr = EndpointAddr::new(peer_pubkey);
                            if let Ok(conn) = endpoint.connect(endpoint_addr, ALPN).await {
                                if let Err(e) = establish_peer_session(inner, conn, peer_pubkey, false).await {
                                    eprintln!("Failed to re-establish session with {}: {:?}", device_id, e);
                                }
                            }
                        }
                    });
                }
            }
        }
    });

    Ok(())
}

async fn handle_incoming_connection(
    inner_arc: Arc<Mutex<NodeInner>>,
    incoming: Incoming,
) -> anyhow::Result<()> {
    let connection = incoming.await?;
    let peer_identity = connection.remote_id();
    
    establish_peer_session(inner_arc, connection, peer_identity, true).await
}

async fn establish_peer_session(
    inner_arc: Arc<Mutex<NodeInner>>,
    connection: Connection,
    peer_identity: PublicKey,
    is_incoming: bool,
) -> anyhow::Result<()> {
    // Open/accept bidirectional stream for message exchange
    let (mut send, recv) = if is_incoming {
        connection.accept_bi().await?
    } else {
        connection.open_bi().await?
    };

    let mut reader = BufReader::new(recv);

    // Perform handshake
    let my_device_name = {
        let inner = inner_arc.lock().await;
        inner.device_name.clone()
    };
    let my_id = {
        let inner = inner_arc.lock().await;
        inner.get_my_node_id()
    };

    if is_incoming {
        // Receive remote handshake first
        let remote_msg = recv_msg(&mut reader).await?;
        if let ProtocolMessage::Handshake { device_id, device_name } = remote_msg {
            // Verify remote device_id matches remote_node_id public key
            if device_id != peer_identity.to_string() {
                return Err(anyhow::anyhow!("Handshake verification failed: ID mismatch"));
            }

            // Check authorization
            let is_authorized = {
                let inner = inner_arc.lock().await;
                let paired = inner.db.get_paired_devices()?.iter().any(|d| d.0 == device_id);
                paired || inner.is_pairing_mode
            };

            if !is_authorized {
                return Err(anyhow::anyhow!("Unauthorized peer attempt"));
            }

            // Save to DB if in pairing mode
            if is_authorized {
                let is_new_pair = {
                    let inner = inner_arc.lock().await;
                    !inner.db.get_paired_devices()?.iter().any(|d| d.0 == device_id)
                };
                if is_new_pair {
                    let inner = inner_arc.lock().await;
                    inner.db.add_paired_device(&device_id, &peer_identity.to_string(), &device_name)?;
                }
            }

            // Send back our handshake
            send_msg(&mut send, &ProtocolMessage::Handshake {
                device_id: my_id,
                device_name: my_device_name,
            }).await?;

            // Setup peer session
            setup_peer_state(inner_arc, connection, peer_identity, device_name, send, reader).await?;
        } else {
            return Err(anyhow::anyhow!("Invalid handshake message"));
        }
    } else {
        // We initiated. Send our handshake first
        send_msg(&mut send, &ProtocolMessage::Handshake {
            device_id: my_id,
            device_name: my_device_name,
            
        }).await?;

        // Receive remote handshake
        let remote_msg = recv_msg(&mut reader).await?;
        if let ProtocolMessage::Handshake { device_id, device_name } = remote_msg {
            if device_id != peer_identity.to_string() {
                return Err(anyhow::anyhow!("Handshake verification failed: ID mismatch"));
            }

            // Save peer connection
            setup_peer_state(inner_arc, connection, peer_identity, device_name, send, reader).await?;
        } else {
            return Err(anyhow::anyhow!("Invalid handshake message"));
        }
    }

    Ok(())
}

async fn setup_peer_state(
    inner_arc: Arc<Mutex<NodeInner>>,
    connection: Connection,
    peer_identity: PublicKey,
    device_name: String,
    mut send: iroh::endpoint::SendStream,
    mut reader: BufReader<iroh::endpoint::RecvStream>,
) -> anyhow::Result<()> {
    let (tx, mut rx) = tokio::sync::mpsc::channel::<ProtocolMessage>(32);

    let connection_state = ConnectionState {
        connection: connection.clone(),
        device_name: device_name.clone(),
        writer_tx: tx,
    };

    // Save to active peers
    {
        let mut inner = inner_arc.lock().await;
        inner.peers.insert(peer_identity, connection_state);
        
        // Notify callback
        if let Some(cb) = &inner.callback {
            cb.on_peer_connected(peer_identity.to_string(), device_name.clone());
        }
    }

    // Spawn write handler
    tokio::spawn(async move {
        while let Some(msg) = rx.recv().await {
            if let Err(e) = send_msg(&mut send, &msg).await {
                eprintln!("Error sending message to peer: {:?}", e);
                break;
            }
        }
    });

    // Spawn read handler
    let inner_clone = inner_arc.clone();
    tokio::spawn(async move {
        loop {
            match recv_msg(&mut reader).await {
                Ok(msg) => {
                    if let Err(e) = handle_protocol_message(inner_clone.clone(), peer_identity, msg).await {
                        eprintln!("Error handling protocol message: {:?}", e);
                    }
                }
                Err(e) => {
                    eprintln!("Session with peer {} ended: {:?}", peer_identity, e);
                    // Remove from active peers
                    let mut inner = inner_clone.lock().await;
                    inner.peers.remove(&peer_identity);
                    if let Some(cb) = &inner.callback {
                        cb.on_peer_disconnected(peer_identity.to_string());
                    }
                    break;
                }
            }
        }
    });

    Ok(())
}

async fn handle_protocol_message(
    inner_arc: Arc<Mutex<NodeInner>>,
    _peer_identity: PublicKey,
    msg: ProtocolMessage,
) -> anyhow::Result<()> {
    let inner = inner_arc.lock().await;
    match msg {
        ProtocolMessage::TrayItem(item) => {
            // Save received item to local SQLite
            inner.db.save_tray_item(&item)?;
            if let Some(cb) = &inner.callback {
                cb.on_tray_item_received(item);
            }
        }
        ProtocolMessage::TrayDelete(delete) => {
            inner.db.delete_tray_item(&delete.item_id)?;
            if let Some(cb) = &inner.callback {
                cb.on_tray_item_deleted(delete.item_id);
            }
        }
        ProtocolMessage::FileIndexUpdate(update) => {
            for item in update.items {
                inner.db.save_file_index(&item)?;
            }
        }
        _ => {}
    }
    Ok(())
}

// Action triggers
pub async fn pair_with_peer(inner_arc: Arc<Mutex<NodeInner>>, ticket_json: &str) -> anyhow::Result<()> {
    let endpoint_addr: EndpointAddr = serde_json::from_str(ticket_json)?;
    let peer_pubkey = endpoint_addr.id;

    // Connect
    let endpoint = {
        let inner = inner_arc.lock().await;
        inner.endpoint.clone().ok_or_else(|| anyhow::anyhow!("Node not started"))?
    };

    let connection = endpoint.connect(endpoint_addr, ALPN).await?;
    
    // Perform handshake and establish session
    establish_peer_session(inner_arc.clone(), connection, peer_pubkey, false).await?;

    // Now save to DB explicitly since handshake succeeded
    let (peer_device_name, _my_id) = {
        let inner = inner_arc.lock().await;
        let peer_name = inner.peers.get(&peer_pubkey).map(|p| p.device_name.clone()).unwrap_or_else(|| "Unknown".to_string());
        (peer_name, inner.get_my_node_id())
    };

    {
        let inner = inner_arc.lock().await;
        inner.db.add_paired_device(&peer_pubkey.to_string(), &peer_pubkey.to_string(), &peer_device_name)?;
    }

    Ok(())
}

pub async fn broadcast_tray_item(inner_arc: Arc<Mutex<NodeInner>>, item: TrayItem) -> anyhow::Result<()> {
    // Save to local db
    {
        let inner = inner_arc.lock().await;
        inner.db.save_tray_item(&item)?;
    }

    // Send to active peers
    let peers_tx = {
        let inner = inner_arc.lock().await;
        inner.peers.values().map(|p| p.writer_tx.clone()).collect::<Vec<_>>()
    };

    for tx in peers_tx {
        let _ = tx.send(ProtocolMessage::TrayItem(item.clone())).await;
    }

    Ok(())
}

pub async fn broadcast_tray_delete(inner_arc: Arc<Mutex<NodeInner>>, item_id: String) -> anyhow::Result<()> {
    // Delete from local db
    {
        let inner = inner_arc.lock().await;
        inner.db.delete_tray_item(&item_id)?;
    }

    // Send to active peers
    let peers_tx = {
        let inner = inner_arc.lock().await;
        inner.peers.values().map(|p| p.writer_tx.clone()).collect::<Vec<_>>()
    };

    for tx in peers_tx {
        let _ = tx.send(ProtocolMessage::TrayDelete(TrayDelete { item_id: item_id.clone() })).await;
    }

    Ok(())
}
