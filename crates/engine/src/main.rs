use tracing_subscriber::EnvFilter;
mod rpc;

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
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
    // Do not put raw transport/JSON errors in stderr: they may contain input.
    if rpc::serve(tokio::io::stdin(), tokio::io::stdout())
        .await
        .is_err()
    {
        tracing::error!("Flow IPC connection terminated");
        std::process::exit(1);
    }
    Ok(())
}
