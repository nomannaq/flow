//! Offline maintenance entry point; acquiring HistoryStore's writer lock excludes
//! a running project engine. Destructive retention needs an exact session ID.
use std::{ffi::OsString, path::PathBuf};

pub fn run(args: Vec<OsString>) -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
    let mut args = args.into_iter();
    let action = args
        .next()
        .and_then(|v| v.into_string().ok())
        .ok_or("missing maintenance action")?;
    let mut project = None;
    let mut data = None;
    let mut session = None;
    let mut budget = None;
    let mut stopped = false;
    let mut owner = None;
    while let Some(arg) = args.next() {
        match arg.to_str() {
            Some("--project-root") => {
                project = Some(PathBuf::from(args.next().ok_or("missing project root")?))
            }
            Some("--data-root") => {
                data = Some(PathBuf::from(args.next().ok_or("missing data root")?))
            }
            Some("--session") => {
                session = Some(
                    protocol::SessionId::try_from(
                        args.next()
                            .ok_or("missing session ID")?
                            .into_string()
                            .map_err(|_| "invalid session ID")?,
                    )
                    .map_err(|_| "invalid session ID")?,
                )
            }
            Some("--bytes") => {
                budget = Some(
                    args.next()
                        .ok_or("missing byte budget")?
                        .into_string()
                        .map_err(|_| "invalid byte budget")?
                        .parse::<u64>()?,
                )
            }
            Some("--writers-stopped") => stopped = true,
            Some("--owner") => {
                owner = Some(
                    args.next()
                        .ok_or("missing previous owner token")?
                        .into_string()
                        .map_err(|_| "invalid owner token")?,
                )
            }
            _ => return Err("unknown maintenance argument".into()),
        }
    }
    if !matches!(
        action.as_str(),
        "inspect" | "budget" | "recover" | "delete-session" | "finish-retention"
    ) {
        return Err("unknown maintenance action".into());
    }
    let project = project.ok_or("--project-root is required")?;
    let store = match data {
        Some(data) => history::HistoryStore::open(&project, &data)?,
        None => history::HistoryStore::open_default(&project)?,
    };
    #[cfg(unix)]
    let recovery = owner.map_or(
        runtime::worktrees::Recovery::RefuseStale,
        runtime::worktrees::Recovery::Acknowledge,
    );
    match action.as_str() {
        "budget" => store.set_storage_budget(budget.ok_or("--bytes is required")?)?,
        "finish-retention" => store.finish_retention()?,
        #[cfg(unix)]
        "recover" | "delete-session" => {
            let session = session.ok_or("--session is required")?;
            let result = if action == "recover" {
                runtime::maintenance::recover_session(store.clone(), session, recovery, stopped)
                    .map(|_| ())
            } else {
                runtime::maintenance::delete_session(store.clone(), session, recovery, stopped)
            };
            if let Err(runtime::worktrees::Error::StaleOwner(ref owner)) = result {
                eprintln!(
                    "Previous owner instance: {} (PID {}). Inspect its processes; --owner acknowledges this exact instance and does not stop writers.",
                    owner.instance, owner.pid
                );
            }
            result?;
        }
        "inspect" => {}
        _ => return Err("workspace maintenance is unsupported on this platform".into()),
    }
    #[cfg(unix)]
    let report = runtime::maintenance::inspect(&store)?;
    #[cfg(not(unix))]
    let report = serde_json::to_value(store.storage_report()?)?;
    println!("{}", serde_json::to_string_pretty(&report)?);
    Ok(())
}
