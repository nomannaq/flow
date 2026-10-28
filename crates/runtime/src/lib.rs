//! Flow's session policy, worktree ownership and ACP SDK integration.
//!
//! The ACP SDK owns agent communication. Flow adds durable lifecycle and
//! recorder coordination. Workspace preparation is blocking and must run off
//! async/IDE event threads. Agent launch is introduced separately in B08.

mod agent_config;
pub mod agents;
pub mod images;
pub mod maintenance;
pub mod recorder;
pub mod review;
mod tool_activity;
pub mod worktrees;

#[cfg(test)]
#[allow(dead_code)]
#[path = "../../../tests/support/repository.rs"]
mod test_repository;

pub mod application;

pub mod local_changes;

pub mod context_bridge;
mod context_review;
pub mod utilities;

pub mod attachments;
