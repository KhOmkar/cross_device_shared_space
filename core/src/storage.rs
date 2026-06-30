use crate::protocol::{TrayContentType, TrayItem, FileIndexItem};
use rusqlite::{params, Connection, Result};
use std::path::Path;

pub struct Database {
    conn: Connection,
}

impl Database {
    pub fn open<P: AsRef<Path>>(path: P) -> Result<Self> {
        let conn = Connection::open(path)?;
        let db = Database { conn };
        db.init()?;
        Ok(db)
    }

    pub fn open_in_memory() -> Result<Self> {
        let conn = Connection::open_in_memory()?;
        let db = Database { conn };
        db.init()?;
        Ok(db)
    }

    fn init(&self) -> Result<()> {
        self.conn.execute(
            "CREATE TABLE IF NOT EXISTS config (
                key TEXT PRIMARY KEY,
                value TEXT NOT NULL
            )",
            [],
        )?;

        self.conn.execute(
            "CREATE TABLE IF NOT EXISTS paired_devices (
                device_id TEXT PRIMARY KEY,
                public_key TEXT NOT NULL,
                device_name TEXT NOT NULL
            )",
            [],
        )?;

        self.conn.execute(
            "CREATE TABLE IF NOT EXISTS tray_items (
                id TEXT PRIMARY KEY,
                content_type TEXT NOT NULL,
                content TEXT NOT NULL,
                source_device TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                expires_at INTEGER NOT NULL
            )",
            [],
        )?;

        self.conn.execute(
            "CREATE TABLE IF NOT EXISTS file_index (
                id TEXT PRIMARY KEY,
                filename TEXT NOT NULL,
                size INTEGER NOT NULL,
                hash TEXT NOT NULL,
                owning_device TEXT NOT NULL,
                path TEXT NOT NULL,
                last_seen INTEGER NOT NULL
            )",
            [],
        )?;

        Ok(())
    }

    pub fn get_config(&self, key: &str) -> Result<Option<String>> {
        let mut stmt = self.conn.prepare("SELECT value FROM config WHERE key = ?")?;
        let mut rows = stmt.query(params![key])?;
        if let Some(row) = rows.next()? {
            let val: String = row.get(0)?;
            Ok(Some(val))
        } else {
            Ok(None)
        }
    }

    pub fn set_config(&self, key: &str, value: &str) -> Result<()> {
        self.conn.execute(
            "INSERT OR REPLACE INTO config (key, value) VALUES (?, ?)",
            params![key, value],
        )?;
        Ok(())
    }

    pub fn add_paired_device(&self, id: &str, public_key: &str, name: &str) -> Result<()> {
        self.conn.execute(
            "INSERT OR REPLACE INTO paired_devices (device_id, public_key, device_name) VALUES (?, ?, ?)",
            params![id, public_key, name],
        )?;
        Ok(())
    }

    pub fn get_paired_devices(&self) -> Result<Vec<(String, String, String)>> {
        let mut stmt = self.conn.prepare("SELECT device_id, public_key, device_name FROM paired_devices")?;
        let rows = stmt.query_map([], |row| {
            Ok((row.get(0)?, row.get(1)?, row.get(2)?))
        })?;
        let mut devices = Vec::new();
        for dev in rows {
            devices.push(dev?);
        }
        Ok(devices)
    }

    pub fn save_tray_item(&self, item: &TrayItem) -> Result<()> {
        let content_type = match item.content_type {
            TrayContentType::Text => "Text",
            TrayContentType::Link => "Link",
            TrayContentType::Image => "Image",
            TrayContentType::File => "File",
        };
        self.conn.execute(
            "INSERT OR REPLACE INTO tray_items (id, content_type, content, source_device, created_at, expires_at)
             VALUES (?, ?, ?, ?, ?, ?)",
            params![
                item.item_id,
                content_type,
                item.content,
                item.source_device_id,
                item.created_at,
                item.expires_at
            ],
        )?;
        Ok(())
    }

    pub fn get_tray_items(&self) -> Result<Vec<TrayItem>> {
        let mut stmt = self.conn.prepare(
            "SELECT id, content_type, content, source_device, created_at, expires_at
             FROM tray_items ORDER BY created_at DESC"
        )?;
        let rows = stmt.query_map([], |row| {
            let type_str: String = row.get(1)?;
            let content_type = match type_str.as_str() {
                "Link" => TrayContentType::Link,
                "Image" => TrayContentType::Image,
                "File" => TrayContentType::File,
                _ => TrayContentType::Text,
            };
            Ok(TrayItem {
                item_id: row.get(0)?,
                content_type,
                content: row.get(2)?,
                source_device_id: row.get(3)?,
                created_at: row.get(4)?,
                expires_at: row.get(5)?,
            })
        })?;
        let mut items = Vec::new();
        for item in rows {
            items.push(item?);
        }
        Ok(items)
    }

    pub fn delete_tray_item(&self, item_id: &str) -> Result<()> {
        self.conn.execute("DELETE FROM tray_items WHERE id = ?", params![item_id])?;
        Ok(())
    }

    pub fn clean_expired_items(&self, now: u64) -> Result<Vec<String>> {
        let mut stmt = self.conn.prepare("SELECT id FROM tray_items WHERE expires_at <= ?")?;
        let rows = stmt.query_map(params![now], |row| row.get::<_, String>(0))?;
        let mut expired_ids = Vec::new();
        for id in rows {
            expired_ids.push(id?);
        }
        self.conn.execute("DELETE FROM tray_items WHERE expires_at <= ?", params![now])?;
        Ok(expired_ids)
    }

    pub fn save_file_index(&self, item: &FileIndexItem) -> Result<()> {
        self.conn.execute(
            "INSERT OR REPLACE INTO file_index (id, filename, size, hash, owning_device, path, last_seen)
             VALUES (?, ?, ?, ?, ?, ?, ?)",
            params![
                item.file_id,
                item.filename,
                item.size,
                item.hash,
                item.owning_device_id,
                item.path,
                item.last_seen
            ],
        )?;
        Ok(())
    }

    pub fn get_file_index(&self) -> Result<Vec<FileIndexItem>> {
        let mut stmt = self.conn.prepare(
            "SELECT id, filename, size, hash, owning_device, path, last_seen FROM file_index"
        )?;
        let rows = stmt.query_map([], |row| {
            Ok(FileIndexItem {
                file_id: row.get(0)?,
                filename: row.get(1)?,
                size: row.get(2)?,
                hash: row.get(3)?,
                owning_device_id: row.get(4)?,
                path: row.get(5)?,
                last_seen: row.get(6)?,
            })
        })?;
        let mut items = Vec::new();
        for item in rows {
            items.push(item?);
        }
        Ok(items)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_db_init_and_config() -> Result<()> {
        let db = Database::open_in_memory()?;
        
        // Test config store & load
        db.set_config("test_key", "test_value")?;
        assert_eq!(db.get_config("test_key")?, Some("test_value".to_string()));
        assert_eq!(db.get_config("nonexistent")?, None);
        
        Ok(())
    }

    #[test]
    fn test_paired_devices() -> Result<()> {
        let db = Database::open_in_memory()?;
        
        db.add_paired_device("dev1", "pubkey1", "Device 1")?;
        db.add_paired_device("dev2", "pubkey2", "Device 2")?;
        
        let devs = db.get_paired_devices()?;
        assert_eq!(devs.len(), 2);
        assert!(devs.contains(&("dev1".to_string(), "pubkey1".to_string(), "Device 1".to_string())));
        assert!(devs.contains(&("dev2".to_string(), "pubkey2".to_string(), "Device 2".to_string())));
        
        Ok(())
    }

    #[test]
    fn test_tray_items() -> Result<()> {
        let db = Database::open_in_memory()?;
        
        let item1 = TrayItem {
            item_id: "item1".to_string(),
            content_type: TrayContentType::Text,
            content: "Hello World".to_string(),
            source_device_id: "dev1".to_string(),
            created_at: 1000,
            expires_at: 2000,
        };
        
        let item2 = TrayItem {
            item_id: "item2".to_string(),
            content_type: TrayContentType::Link,
            content: "https://example.com".to_string(),
            source_device_id: "dev2".to_string(),
            created_at: 1100,
            expires_at: 1200,
        };
        
        db.save_tray_item(&item1)?;
        db.save_tray_item(&item2)?;
        
        let items = db.get_tray_items()?;
        assert_eq!(items.len(), 2);
        assert_eq!(items[0].item_id, "item2"); // Ordered by created_at DESC (1100 > 1000)
        
        // Clean expired
        let expired = db.clean_expired_items(1500)?;
        assert_eq!(expired.len(), 1);
        assert_eq!(expired[0], "item2");
        
        let items_after = db.get_tray_items()?;
        assert_eq!(items_after.len(), 1);
        assert_eq!(items_after[0].item_id, "item1");
        
        Ok(())
    }
}

