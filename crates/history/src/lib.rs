//! Immutable workspace observations and durable local history.
//!
//! B04 introduces SQLite metadata and content-addressed blobs. Keep this crate
//! independent of agent protocols and IDE APIs so its correctness can be tested
//! without an agent or editor.
