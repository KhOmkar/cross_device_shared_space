uniffi::setup_scaffolding!();

pub mod protocol;
pub mod storage;
pub mod network;

use std::sync::Arc;
use tokio::sync::Mutex;
use std::time::{SystemTime, UNIX_EPOCH};

// Re-export record types for UniFFI
pub use protocol::{TrayContentType, TrayItem, FileIndexItem};
pub use network::NodeCallback;

#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum SharedSpaceError {
    #[error("Database error: {0}")]
    Database(String),
    #[error("Network error: {0}")]
    Network(String),
    #[error("Serialization error: {0}")]
    Serialization(String),
    #[error("General error: {0}")]
    General(String),
}

impl From<anyhow::Error> for SharedSpaceError {
    fn from(err: anyhow::Error) -> Self {
        SharedSpaceError::General(err.to_string())
    }
}

impl From<rusqlite::Error> for SharedSpaceError {
    fn from(err: rusqlite::Error) -> Self {
        SharedSpaceError::Database(err.to_string())
    }
}

impl From<serde_json::Error> for SharedSpaceError {
    fn from(err: serde_json::Error) -> Self {
        SharedSpaceError::Serialization(err.to_string())
    }
}

#[derive(uniffi::Record)]
pub struct PairedDevice {
    pub device_id: String,
    pub public_key: String,
    pub device_name: String,
}

#[derive(uniffi::Object)]
pub struct SharedSpaceNode {
    inner: Arc<Mutex<network::NodeInner>>,
    runtime: tokio::runtime::Runtime,
}

#[uniffi::export(callback_interface)]
pub trait NodeCallbackInterface: Send + Sync {
    fn on_tray_item_received(&self, item: TrayItem);
    fn on_tray_item_deleted(&self, item_id: String);
    fn on_peer_connected(&self, device_id: String, name: String);
    fn on_peer_disconnected(&self, device_id: String);
}

// Internal bridge from UniFFI callback to NodeCallback trait
struct CallbackBridge {
    cb: Box<dyn NodeCallbackInterface>,
}

impl network::NodeCallback for CallbackBridge {
    fn on_tray_item_received(&self, item: TrayItem) {
        self.cb.on_tray_item_received(item);
    }
    fn on_tray_item_deleted(&self, item_id: String) {
        self.cb.on_tray_item_deleted(item_id);
    }
    fn on_peer_connected(&self, device_id: String, name: String) {
        self.cb.on_peer_connected(device_id, name);
    }
    fn on_peer_disconnected(&self, device_id: String) {
        self.cb.on_peer_disconnected(device_id);
    }
}

#[uniffi::export]
impl SharedSpaceNode {
    #[uniffi::constructor]
    pub fn new(
        db_path: String,
        device_name: String,
        callback: Box<dyn NodeCallbackInterface>,
    ) -> Result<Arc<Self>, SharedSpaceError> {
        let rt = tokio::runtime::Builder::new_multi_thread()
            .enable_all()
            .build()
            .map_err(|e| SharedSpaceError::General(e.to_string()))?;

        let bridge = Arc::new(CallbackBridge { cb: callback });
        
        let inner = network::NodeInner::new(&db_path, device_name, Some(bridge))?;
        
        Ok(Arc::new(SharedSpaceNode {
            inner: Arc::new(Mutex::new(inner)),
            runtime: rt,
        }))
    }

    pub fn start(&self) -> Result<(), SharedSpaceError> {
        let inner = self.inner.clone();
        self.runtime.block_on(async move {
            network::start_node(inner).await?;
            Ok(())
        })
    }

    pub fn get_my_node_id(&self) -> String {
        self.runtime.block_on(async {
            let inner = self.inner.lock().await;
            inner.get_my_node_id()
        })
    }

    pub fn get_pairing_ticket(&self) -> Result<String, SharedSpaceError> {
        self.runtime.block_on(async {
            let inner = self.inner.lock().await;
            let ticket = inner.get_pairing_ticket()?;
            Ok(ticket)
        })
    }

    pub fn set_pairing_mode(&self, active: bool) {
        self.runtime.block_on(async {
            let mut inner = self.inner.lock().await;
            inner.set_pairing_mode(active);
        })
    }

    pub fn pair_with_peer(&self, ticket_json: String) -> Result<(), SharedSpaceError> {
        let inner = self.inner.clone();
        self.runtime.block_on(async move {
            network::pair_with_peer(inner, &ticket_json).await?;
            Ok(())
        })
    }

    pub fn get_tray_items(&self) -> Result<Vec<TrayItem>, SharedSpaceError> {
        self.runtime.block_on(async {
            let inner = self.inner.lock().await;
            let items = inner.db.get_tray_items()?;
            Ok(items)
        })
    }

    pub fn add_tray_item(
        &self,
        content: String,
        content_type: TrayContentType,
        duration_secs: u64,
    ) -> Result<TrayItem, SharedSpaceError> {
        self.runtime.block_on(async {
            let now = SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .unwrap()
                .as_secs();

            let my_id = {
                let inner = self.inner.lock().await;
                inner.get_my_node_id()
            };

            let item_id = uuid::Uuid::new_v4().to_string();
            let item = TrayItem {
                item_id,
                content_type,
                content,
                source_device_id: my_id,
                created_at: now,
                expires_at: now + duration_secs,
            };

            let inner = self.inner.clone();
            network::broadcast_tray_item(inner, item.clone()).await?;

            Ok(item)
        })
    }

    pub fn delete_tray_item(&self, item_id: String) -> Result<(), SharedSpaceError> {
        let inner = self.inner.clone();
        self.runtime.block_on(async move {
            network::broadcast_tray_delete(inner, item_id).await?;
            Ok(())
        })
    }

    pub fn get_paired_devices(&self) -> Result<Vec<PairedDevice>, SharedSpaceError> {
        self.runtime.block_on(async {
            let inner = self.inner.lock().await;
            let raw_devs = inner.db.get_paired_devices()?;
            let devs = raw_devs
                .into_iter()
                .map(|(id, pk, name)| PairedDevice {
                    device_id: id,
                    public_key: pk,
                    device_name: name,
                })
                .collect();
            Ok(devs)
        })
    }

    pub fn clean_expired_items(&self) -> Result<Vec<String>, SharedSpaceError> {
        self.runtime.block_on(async {
            let now = SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .unwrap()
                .as_secs();
            
            let expired_ids = {
                let inner = self.inner.lock().await;
                inner.db.clean_expired_items(now)?
            };

            for id in &expired_ids {
                let inner = self.inner.clone();
                if let Err(e) = network::broadcast_tray_delete(inner, id.clone()).await {
                    eprintln!("Failed to broadcast delete for expired item {}: {:?}", id, e);
                }
            }

            Ok(expired_ids)
        })
    }
}
