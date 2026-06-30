use serde::{Deserialize, Serialize};

#[derive(Debug, Serialize, Deserialize, Clone, PartialEq, Eq, uniffi::Enum)]
pub enum TrayContentType {
    Text,
    Link,
    Image,
    File,
}

#[derive(Debug, Serialize, Deserialize, Clone, uniffi::Record)]
pub struct TrayItem {
    pub item_id: String,
    pub content_type: TrayContentType,
    pub content: String, // Hex or Base64 for images/files, plain text for text/links
    pub source_device_id: String,
    pub created_at: u64, // Unix timestamp in seconds
    pub expires_at: u64,
}

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct TrayDelete {
    pub item_id: String,
}

#[derive(Debug, Serialize, Deserialize, Clone, uniffi::Record)]
pub struct FileIndexItem {
    pub file_id: String, // hash
    pub filename: String,
    pub size: u64,
    pub hash: String,
    pub owning_device_id: String,
    pub path: String,
    pub last_seen: u64,
}

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct FileIndexUpdate {
    pub items: Vec<FileIndexItem>,
}

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct FileRequest {
    pub file_id: String,
    pub chunk_index: u64,
}

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct FileChunk {
    pub file_id: String,
    pub chunk_index: u64,
    pub chunk_bytes: String, // Base64 encoded chunk
    pub total_chunks: u64,
}

#[derive(Debug, Serialize, Deserialize, Clone)]
pub enum ProtocolMessage {
    Handshake { device_id: String, device_name: String },
    TrayItem(TrayItem),
    TrayDelete(TrayDelete),
    FileIndexUpdate(FileIndexUpdate),
    FileRequest(FileRequest),
    FileChunk(FileChunk),
}
