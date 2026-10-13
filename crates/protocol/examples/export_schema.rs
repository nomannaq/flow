fn main() -> Result<(), Box<dyn std::error::Error>> {
    let schema = serde_json::to_string_pretty(&protocol::schema())? + "\n";
    if let Some(path) = std::env::args_os().nth(1) {
        std::fs::write(path, schema)?;
    } else {
        print!("{schema}");
    }
    Ok(())
}
