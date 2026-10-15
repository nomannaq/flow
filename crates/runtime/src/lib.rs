//! Flow's session policy, worktree ownership and ACP SDK integration.
//!
//! The ACP SDK owns agent communication. Flow adds durable lifecycle and
//! recorder coordination. Workspace preparation is blocking and must run off
//! async/IDE event threads. Agent launch is introduced separately in B08.

pub mod agents;
pub mod maintenance;
pub mod recorder;
pub mod worktrees;

#[cfg(all(test, unix))]
#[allow(dead_code)]
#[path = "../../../tests/support/repository.rs"]
mod test_repository;
