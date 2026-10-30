use agent_client_protocol::{Error, schema::v1 as acp};
use base64::{Engine as _, engine::general_purpose::STANDARD};

pub fn decode(images: &[protocol::PromptImage]) -> Result<Vec<history::AttachmentInput>, Error> {
    if images.len() > 4 {
        return Err(Error::invalid_params());
    }
    images
        .iter()
        .map(|image| {
            if image.name.is_empty()
                || image.name.chars().count() > 128
                || image.name.chars().any(char::is_control)
                || image.data.len() > 175_000
            {
                return Err(Error::invalid_params());
            }
            let bytes = STANDARD
                .decode(&image.data)
                .map_err(|_| Error::invalid_params())?;
            if bytes.is_empty()
                || bytes.len() > 128 * 1024
                || !match image.mime_type.as_str() {
                    "image/png" => bytes.starts_with(b"\x89PNG\r\n\x1a\n"),
                    "image/jpeg" => bytes.starts_with(&[0xff, 0xd8, 0xff]),
                    _ => false,
                }
            {
                return Err(Error::invalid_params());
            }
            Ok(history::AttachmentInput {
                name: image.name.clone(),
                mime_type: image.mime_type.clone(),
                bytes,
            })
        })
        .collect()
}
pub fn blocks(images: &[protocol::PromptImage]) -> Vec<acp::ContentBlock> {
    images
        .iter()
        .map(|i| {
            acp::ContentBlock::Image(acp::ImageContent::new(i.data.clone(), i.mime_type.clone()))
        })
        .collect()
}
pub fn read(
    store: history::HistoryStore,
    query: protocol::ImageQuery,
) -> history::Result<serde_json::Value> {
    let bytes = store.image_bytes(query.session_id, query.turn_id, query.image_id)?;
    Ok(serde_json::json!({"data":STANDARD.encode(bytes)}))
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn accepts_bounded_inline_images_and_rejects_urls_mismatch_and_overflow() {
        let image = protocol::PromptImage {
            name: "diagram.png".into(),
            mime_type: "image/png".into(),
            data: STANDARD.encode(b"\x89PNG\r\n\x1a\nbytes"),
        };
        assert_eq!(
            decode(std::slice::from_ref(&image)).unwrap()[0].bytes,
            b"\x89PNG\r\n\x1a\nbytes"
        );
        assert!(matches!(
            blocks(std::slice::from_ref(&image))[0],
            acp::ContentBlock::Image(_)
        ));
        let mut invalid = image.clone();
        invalid.data = "https://example.com/private.png".into();
        assert!(decode(&[invalid]).is_err());
        let mut invalid = image.clone();
        invalid.mime_type = "image/jpeg".into();
        assert!(decode(&[invalid]).is_err());
        let mut invalid = image.clone();
        invalid.data = STANDARD.encode(vec![0; 128 * 1024 + 1]);
        assert!(decode(&[invalid]).is_err());
        assert!(decode(&vec![image; 5]).is_err());
    }
}
