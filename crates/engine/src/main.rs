use tracing_subscriber::EnvFilter;

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
    tracing_subscriber::fmt()
        .with_env_filter(
            EnvFilter::try_from_default_env().unwrap_or_else(|_| EnvFilter::new("info")),
        )
        .with_writer(std::io::stderr)
        .with_ansi(false)
        .try_init()?;

    // Stdout is reserved for Flow IPC; B03 will add the request loop.
    tracing::info!(
        version = env!("CARGO_PKG_VERSION"),
        "Flow engine bootstrap ready"
    );
    Ok(())
}
