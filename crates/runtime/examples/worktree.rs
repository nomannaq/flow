//! B07 manual exercise. Does not start an agent or change the main working tree.
#[cfg(unix)]
fn main() {
    use runtime::worktrees::{BaselineChoice, Error, Recovery, WorktreeManager};
    use std::path::Path;
    fn run() -> Result<(), Error> {
        let mut args = std::env::args().skip(1);
        let mut repository_recovery = Recovery::RefuseStale;
        let mut writer_recovery = Recovery::RefuseStale;
        let mut positional = Vec::new();
        while let Some(arg) = args.next() {
            match arg.as_str() {
                "--recover-repository" => {
                    repository_recovery = Recovery::Acknowledge(
                        args.next()
                            .ok_or(Error::Invalid("missing repository owner token"))?,
                    )
                }
                "--recover-writer" => {
                    writer_recovery = Recovery::Acknowledge(
                        args.next()
                            .ok_or(Error::Invalid("missing writer owner token"))?,
                    )
                }
                _ => positional.push(arg),
            }
        }
        let [project, data, action, rest @ ..] = positional.as_slice() else {
            return Err(Error::Invalid(
                "usage: worktree PROJECT EXISTING_DATA_DIR {prepare COMMIT|list|inspect ID|cleanup ID} [--recover-repository TOKEN] [--recover-writer TOKEN]",
            ));
        };
        let project = Path::new(project);
        let store = history::HistoryStore::open(project, Path::new(data))?;
        let manager = WorktreeManager::open(project, store, repository_recovery)?;
        let value = match (action.as_str(), rest) {
            ("prepare", [commit]) => {
                let guard = manager.prepare_with_recovery(
                    BaselineChoice::CleanCommit(commit.clone()),
                    writer_recovery,
                )?;
                serde_json::to_value(&guard.preparation)
            }
            ("list", []) => serde_json::to_value(manager.preparations()?),
            ("inspect", [id]) => {
                let id = protocol::WorkspaceId::try_from(id.clone())
                    .map_err(|_| Error::Invalid("invalid workspace ID"))?;
                let guard = manager.acquire(&id, writer_recovery)?;
                serde_json::to_value(&guard.preparation)
            }
            ("cleanup", [id]) => {
                let id = protocol::WorkspaceId::try_from(id.clone())
                    .map_err(|_| Error::Invalid("invalid workspace ID"))?;
                manager.cleanup_incomplete(&id, writer_recovery)?;
                serde_json::to_value(manager.preparations()?)
            }
            _ => {
                return Err(Error::Invalid(
                    "expected prepare COMMIT, list, inspect ID, or cleanup ID",
                ));
            }
        }
        .map_err(|_| Error::Invalid("could not encode preparation"))?;
        println!(
            "{}",
            serde_json::to_string_pretty(&value)
                .map_err(|_| Error::Invalid("could not encode preparation"))?
        );
        Ok(())
    }
    if let Err(error) = run() {
        eprintln!("{error}");
        if let Error::StaleOwner(owner) = error {
            eprintln!(
                "Previous owner: PID {}, instance {}",
                owner.pid, owner.instance
            );
        }
        std::process::exit(1);
    }
}
#[cfg(not(unix))]
fn main() {
    eprintln!("B07 worktree preparation currently targets Unix hosts");
    std::process::exit(1);
}
