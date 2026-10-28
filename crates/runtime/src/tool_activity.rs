use protocol::{AgentToolCall, AgentToolDiff, AgentToolLocation};
use serde_json::Value;

fn clipped(text: &str, limit: usize, truncated: &mut bool) -> String {
    let mut end = text.len().min(limit);
    while !text.is_char_boundary(end) {
        end -= 1;
    }
    *truncated |= end < text.len();
    text[..end].to_owned()
}

fn display(value: &Value) -> String {
    value
        .as_str()
        .map(str::to_owned)
        .unwrap_or_else(|| value.to_string())
}

/// Project only presentation fields. Opaque metadata, binary content and credentials
/// in adapter transport errors are never used as tool output.
pub(crate) fn project(value: &Value) -> AgentToolCall {
    let initial = value["sessionUpdate"] == "tool_call";
    let mut truncated = false;
    let mut field = |key: &str, limit| {
        value[key]
            .as_str()
            .map(|text| clipped(text, limit, &mut truncated))
    };
    let mut tool = AgentToolCall {
        title: field("title", 512),
        name: field("name", 128),
        kind: field("kind", 32).or_else(|| initial.then(|| "other".into())),
        status: field("status", 32).or_else(|| initial.then(|| "pending".into())),
        ..AgentToolCall::default()
    };
    // Whitelist only routing/display annotations used by native ACP adapters.
    // The remaining opaque metadata is intentionally not retained.
    tool.name = tool.name.or_else(|| {
        value["_meta"]["claudeCode"]["toolName"]
            .as_str()
            .or(value["_meta"]["codex"]["toolName"].as_str())
            .or(value["_meta"]["toolName"].as_str())
            .map(|name| clipped(name, 128, &mut truncated))
    });
    if tool.name.is_none()
        && tool.title.as_deref().is_some_and(|name| {
            matches!(
                name,
                "spawn_agent"
                    | "wait"
                    | "wait_agent"
                    | "send_input"
                    | "close_agent"
                    | "update_plan"
            )
        })
    {
        tool.name = tool.title.clone();
    }
    tool.parent_tool_call_id = value["_meta"]["claudeCode"]["parentToolUseId"]
        .as_str()
        .or(value["_meta"]["codex"]["parentToolCallId"].as_str())
        .filter(|id| !id.is_empty() && id.len() <= 256 && !id.chars().any(char::is_control))
        .map(str::to_owned);
    tool.input = value
        .get("rawInput")
        .filter(|v| !v.is_null())
        .map(|v| clipped(&display(v), 4096, &mut truncated));
    tool.output = value
        .get("rawOutput")
        .filter(|v| !v.is_null())
        .map(|v| clipped(&display(v), 4096, &mut truncated));
    if let Some(locations) = value["locations"].as_array() {
        truncated |= locations.len() > 8;
        tool.locations = Some(
            locations
                .iter()
                .take(8)
                .filter_map(|item| {
                    Some(AgentToolLocation {
                        path: clipped(item["path"].as_str()?, 512, &mut truncated),
                        line: item["line"].as_u64().and_then(|n| u32::try_from(n).ok()),
                    })
                })
                .collect(),
        );
    }
    if let Some(content) = value["content"].as_array() {
        let mut output = String::new();
        let mut diffs = Vec::new();
        for item in content.iter().take(32) {
            match item["type"].as_str() {
                Some("content") => {
                    let body = &item["content"];
                    let text = body["text"].as_str().or(body["resource"]["text"].as_str());
                    if let Some(text) = text {
                        if !output.is_empty() && output.len() < 4096 {
                            output.push('\n');
                        }
                        let remaining = 4096usize.saturating_sub(output.len());
                        output.push_str(&clipped(text, remaining, &mut truncated));
                    } else {
                        truncated = true;
                    }
                }
                Some("diff") if diffs.len() < 2 => {
                    diffs.push(AgentToolDiff {
                        path: clipped(item["path"].as_str().unwrap_or(""), 512, &mut truncated),
                        before: clipped(
                            item["oldText"].as_str().unwrap_or(""),
                            4096,
                            &mut truncated,
                        ),
                        after: clipped(
                            item["newText"].as_str().unwrap_or(""),
                            4096,
                            &mut truncated,
                        ),
                    });
                }
                _ => truncated = true,
            }
        }
        truncated |= content.len() > 32;
        if !output.is_empty() || tool.output.is_none() {
            tool.output = Some(output);
        }
        tool.diffs = Some(diffs);
    }
    tool.truncated = truncated;
    bound(&mut tool);
    tool
}

fn bound(tool: &mut AgentToolCall) {
    let mut truncated = false;
    // JSON escaping can multiply the size of control-heavy content. Keep the
    // whole payload below the durable event limit, not just each raw field.
    while serde_json::to_vec(&tool)
        .expect("tool display is JSON")
        .len()
        > 16 * 1024
    {
        tool.truncated = true;
        for text in tool
            .title
            .iter_mut()
            .chain(tool.name.iter_mut())
            .chain(tool.input.iter_mut())
            .chain(tool.output.iter_mut())
        {
            *text = clipped(text, text.len() / 2, &mut truncated);
        }
        for location in tool.locations.iter_mut().flatten() {
            location.path = clipped(&location.path, location.path.len() / 2, &mut truncated);
        }
        for diff in tool.diffs.iter_mut().flatten() {
            diff.path = clipped(&diff.path, diff.path.len() / 2, &mut truncated);
            diff.before = clipped(&diff.before, diff.before.len() / 2, &mut truncated);
            diff.after = clipped(&diff.after, diff.after.len() / 2, &mut truncated);
        }
    }
}

pub(crate) fn merge(previous: &mut AgentToolCall, next: AgentToolCall) {
    macro_rules! replace {
        ($($field:ident),*) => { $(if next.$field.is_some() { previous.$field = next.$field; })* };
    }
    replace!(
        title,
        name,
        parent_tool_call_id,
        kind,
        status,
        input,
        output,
        locations,
        diffs
    );
    previous.truncated |= next.truncated;
    bound(previous);
}

pub(crate) fn size(tool: &AgentToolCall) -> usize {
    [
        &tool.title,
        &tool.name,
        &tool.parent_tool_call_id,
        &tool.kind,
        &tool.status,
        &tool.input,
        &tool.output,
    ]
    .into_iter()
    .flatten()
    .map(String::len)
    .sum::<usize>()
        + tool
            .locations
            .iter()
            .flatten()
            .map(|v| v.path.len())
            .sum::<usize>()
        + tool
            .diffs
            .iter()
            .flatten()
            .map(|v| v.path.len() + v.before.len() + v.after.len())
            .sum::<usize>()
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn native_annotations_preserve_tool_identity_and_parent_without_opaque_metadata() {
        let mut tool = project(
            &json!({"sessionUpdate":"tool_call", "title":"Read source", "_meta":{"claudeCode":{"toolName":"Read", "parentToolUseId":"delegate-1", "secret":"not-retained"}}}),
        );
        assert_eq!(tool.name.as_deref(), Some("Read"));
        assert_eq!(tool.parent_tool_call_id.as_deref(), Some("delegate-1"));
        merge(
            &mut tool,
            project(&json!({"sessionUpdate":"tool_call_update", "status":"completed"})),
        );
        assert_eq!(tool.parent_tool_call_id.as_deref(), Some("delegate-1"));
        assert!(
            !serde_json::to_string(&tool)
                .unwrap()
                .contains("not-retained")
        );
        assert!(
            project(&json!({"_meta":{"claudeCode":{"parentToolUseId":"bad\nparent"}}}))
                .parent_tool_call_id
                .is_none()
        );
    }

    #[test]
    fn patches_replace_collections_and_keep_missing_fields() {
        let mut tool = project(
            &json!({"sessionUpdate":"tool_call","title":"Read source","name":"read_file","kind":"read","rawInput":{"path":"src/lib.rs"},"locations":[{"path":"src/lib.rs","line":2}]}),
        );
        assert_eq!(tool.status.as_deref(), Some("pending"));
        merge(
            &mut tool,
            project(
                &json!({"sessionUpdate":"tool_call_update","status":"completed","content":[{"type":"content","content":{"type":"text","text":"done"}}]}),
            ),
        );
        assert_eq!(tool.title.as_deref(), Some("Read source"));
        assert_eq!(tool.kind.as_deref(), Some("read"));
        assert_eq!(tool.output.as_deref(), Some("done"));
        assert!(tool.input.as_ref().unwrap().contains("src/lib.rs"));
        merge(
            &mut tool,
            project(&json!({"sessionUpdate":"tool_call_update","content":[],"locations":[]})),
        );
        assert_eq!(tool.output.as_deref(), Some(""));
        assert!(tool.locations.unwrap().is_empty());
        assert!(tool.diffs.unwrap().is_empty());
    }

    #[test]
    fn oversized_unicode_and_escaped_output_remain_bounded_without_binary_or_metadata() {
        let large = "中👩🏽‍💻\0\n".repeat(8000);
        let tool = project(
            &json!({"sessionUpdate":"tool_call","title":large,"rawInput":large,"rawOutput":large,"_meta":{"secret":"private-meta"},"locations":[{"path":"src/lib.rs"}],"content":[{"type":"content","content":{"type":"image","data":"private-binary"}},{"type":"diff","path":"src/lib.rs","oldText":large,"newText":large}]}),
        );
        let json = serde_json::to_string(&tool).unwrap();
        assert!(json.len() <= 16 * 1024);
        assert!(tool.truncated);
        assert!(!json.contains("private-meta") && !json.contains("private-binary"));
        let before = &tool.diffs.unwrap()[0].before;
        assert!(!before.is_empty() && large.starts_with(before));
    }
}
