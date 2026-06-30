use clap::{Parser, Subcommand};
use shared_core::{SharedSpaceNode, NodeCallbackInterface, TrayItem, TrayContentType};
use std::time::Duration;

#[derive(Parser)]
#[command(name = "shared-space")]
#[command(about = "Cross-Device Shared Space CLI & Daemon", long_about = None)]
struct Cli {
    #[arg(short, long, default_value = "LinuxDevice")]
    name: String,

    #[arg(short, long)]
    db: Option<String>,

    #[command(subcommand)]
    command: Commands,
}

#[derive(Subcommand, Clone)]
enum Commands {
    /// Start the background sync daemon
    Start,
    /// Generate and display the pairing ticket
    Ticket,
    /// Enable pairing mode temporarily and wait for peer
    PairMode,
    /// Pair with a remote peer using their ticket
    Pair {
        ticket: String,
    },
    /// Add a new text item to the shared tray
    Add {
        content: String,
    },
    /// List all synced tray items
    List,
    /// Delete a tray item by ID
    Delete {
        id: String,
    },
}

struct CliCallback;

impl NodeCallbackInterface for CliCallback {
    fn on_tray_item_received(&self, item: TrayItem) {
        println!("\n[Received Tray Item] ID: {} Type: {:?} Content: {}", item.item_id, item.content_type, item.content);
    }

    fn on_tray_item_deleted(&self, item_id: String) {
        println!("\n[Deleted Tray Item] ID: {}", item_id);
    }

    fn on_peer_connected(&self, device_id: String, name: String) {
        println!("\n[Peer Connected] Name: {} ID: {}", name, device_id);
    }

    fn on_peer_disconnected(&self, device_id: String) {
        println!("\n[Peer Disconnected] ID: {}", device_id);
    }
}

fn main() -> anyhow::Result<()> {
    let cli = Cli::parse();

    let home = std::env::var("HOME").unwrap_or_else(|_| ".".to_string());
    let db_path = cli.db.unwrap_or_else(|| format!("{}/.shared_space.db", home));

    let callback = Box::new(CliCallback);
    let node = SharedSpaceNode::new(db_path, cli.name, callback)?;

    match cli.command {
        Commands::Start => {
            println!("Starting Shared Space node...");
            node.start()?;
            println!("Node started successfully. My ID: {}", node.get_my_node_id());
            
            // Periodically clean expired items
            let node_clone = node.clone();
            std::thread::spawn(move || loop {
                std::thread::sleep(Duration::from_secs(60));
                if let Err(e) = node_clone.clean_expired_items() {
                    eprintln!("Error cleaning expired items: {:?}", e);
                }
            });

            // Keep the main thread alive
            println!("Press Ctrl+C to stop.");
            loop {
                std::thread::sleep(Duration::from_secs(3600));
            }
        }
        Commands::Ticket => {
            node.start()?; // Need endpoint bound to get ticket
            match node.get_pairing_ticket() {
                Ok(ticket) => {
                    println!("--- PAIRING TICKET (Share this JSON with remote peer) ---");
                    println!("{}", ticket);
                    println!("---------------------------------------------------------");
                }
                Err(e) => {
                    eprintln!("Error generating ticket (is another daemon running?): {:?}", e);
                }
            }
        }
        Commands::PairMode => {
            println!("Starting node and enabling pairing mode...");
            node.start()?;
            node.set_pairing_mode(true);
            println!("Pairing mode active. My ID: {}", node.get_my_node_id());
            println!("Waiting for remote pairing request. Press Ctrl+C to stop.");
            loop {
                std::thread::sleep(Duration::from_secs(3600));
            }
        }
        Commands::Pair { ticket } => {
            println!("Attempting to pair with peer...");
            node.start()?;
            node.pair_with_peer(ticket)?;
            println!("Pairing successful and peer stored.");
        }
        Commands::Add { content } => {
            let item = node.add_tray_item(content, TrayContentType::Text, 24 * 3600)?;
            println!("Added Tray Item. ID: {}", item.item_id);
        }
        Commands::List => {
            let items = node.get_tray_items()?;
            println!("--- SHARED TRAY ITEMS ---");
            for item in items {
                println!("[{}] (Type: {:?})", item.item_id, item.content_type);
                println!("  Content: {}", item.content);
                println!("  Source Device: {}", item.source_device_id);
                println!("  Created: {}", item.created_at);
            }
            println!("-------------------------");
        }
        Commands::Delete { id } => {
            node.delete_tray_item(id)?;
            println!("Broadcasted delete request.");
        }
    }

    Ok(())
}
