mod candidates;
mod session_history;
use tracing_subscriber::EnvFilter;
mod code_context;
mod maintenance;
mod productivity;
mod rpc;

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
    if std::env::args_os().nth(1).as_deref() == Some(std::ffi::OsStr::new("mcp-bridge")) {
        return runtime::context_bridge::run().await;
    }
    if std::env::args_os().nth(1).as_deref() == Some(std::ffi::OsStr::new("--agent-launch")) {
        let _ = runtime::agents::launch(std::env::args_os().skip(2)).await;
        eprintln!("Flow adapter launcher failed");
        std::process::exit(1);
    }
    if std::env::args_os().nth(1).as_deref() == Some(std::ffi::OsStr::new("--context")) {
        let args = std::env::args_os().skip(2).collect();
        return tokio::task::spawn_blocking(move || code_context::run(args)).await?;
    }
    tracing_subscriber::fmt()
        .with_env_filter(
            EnvFilter::try_from_default_env().unwrap_or_else(|_| EnvFilter::new("info")),
        )
        .with_writer(std::io::stderr)
        .with_ansi(false)
        .try_init()?;

    tracing::info!(
        version = env!("CARGO_PKG_VERSION"),
        "Flow engine bootstrap ready"
    );
    if std::env::args_os().nth(1).as_deref() == Some(std::ffi::OsStr::new("--history")) {
        let args = std::env::args_os().skip(2).collect();
        if let Err(error) = tokio::task::spawn_blocking(move || maintenance::run(args)).await? {
            eprintln!("History maintenance failed: {error}");
            std::process::exit(1);
        }
        return Ok(());
    }
    // Explicit host-owned locations only. Bare stdio mode remains side-effect free.
    let args: Vec<_> = std::env::args_os().skip(1).collect();
    let store = if args.is_empty() {
        None
    } else if (args.len() == 2 || (args.len() == 4 && args[2] == "--data-root"))
        && args[0] == "--project-root"
    {
        let project = std::path::PathBuf::from(&args[1]);
        let data = args.get(3).map(std::path::PathBuf::from);
        match tokio::task::spawn_blocking(move || match data {
            Some(data) => history::HistoryStore::open(&project, &data),
            None => history::HistoryStore::open_default(&project),
        })
        .await?
        {
            Ok(store) => {
                let recovery = store.clone();
                tokio::task::spawn_blocking(move || {
                    if recovery.storage_report()?.writable {
                        // A failed recovery write leaves metadata readable and disables
                        // further writes; the engine must still offer inspection.
                        let _ = recovery.interrupt_agent_sessions();
                    }
                    match runtime::maintenance::inspect(&recovery) {
                        Ok(report) => {
                            let needs_review = report["sessions"].as_array().is_some_and(|sessions| sessions.iter().any(|s| s["status"] != "closed" || s["worktree"] != "matches_checkpoint"));
                            tracing::info!(needs_review, "History startup reconciliation finished; no agent mutations replayed");
                        },
                        Err(_) => tracing::warn!("History workspace inspection incomplete; retained data requires review"),
                    }
                    Ok::<_,history::Error>(())
                }).await??;
                Some(store)
            }
            Err(_) => {
                tracing::error!("Flow project history could not be opened");
                std::process::exit(1);
            }
        }
    } else {
        tracing::error!("Expected --project-root PATH [--data-root PATH]");
        std::process::exit(1);
    };
    // Do not put raw transport/JSON errors in stderr: they may contain input.
    let result = if let Some(store) = store {
        rpc::serve_with_store(tokio::io::stdin(), tokio::io::stdout(), Some(store)).await
    } else {
        rpc::serve(tokio::io::stdin(), tokio::io::stdout()).await
    };
    if result.is_err() {
        tracing::error!("Flow IPC connection terminated");
        std::process::exit(1);
    }
    Ok(())
}
