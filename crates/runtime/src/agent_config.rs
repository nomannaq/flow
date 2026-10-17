//! Bounded, credential-free projection of the adapter's advertised session controls.
use agent_client_protocol::Error;
use protocol::{AgentConfigChoice, AgentConfigOption, AgentConfigure};
use serde_json::Value;

fn text(value: &Value, key: &str, max: usize) -> Result<String, Error> {
    let result = value[key].as_str().unwrap_or_default();
    if result.len() > max
        || result
            .chars()
            .any(|c| c.is_control() && !(key == "description" && matches!(c, '\n' | '\t' | '\r')))
    {
        return Err(Error::invalid_params());
    }
    Ok(result.to_owned())
}

pub(crate) fn project(options: &Value, modes: &Value) -> Result<Vec<AgentConfigOption>, Error> {
    let mut result = Vec::new();
    if let Some(options) = options.as_array() {
        if options.len() > 64 {
            return Err(Error::invalid_params());
        }
        for option in options {
            let kind = text(option, "type", 32)?;
            if !matches!(kind.as_str(), "select" | "boolean") {
                continue;
            }
            let mut choices = Vec::new();
            if let Some(items) = option["options"].as_array() {
                for item in items {
                    let (items, group) = if let Some(items) = item["options"].as_array() {
                        (items.as_slice(), text(item, "name", 256)?)
                    } else {
                        (std::slice::from_ref(item), String::new())
                    };
                    for item in items {
                        choices.push(AgentConfigChoice {
                            value: text(item, "value", 256)?,
                            name: text(item, "name", 256)?,
                            description: text(item, "description", 2048)?,
                            group: group.clone(),
                        });
                        if choices.len() > 256 {
                            return Err(Error::invalid_params());
                        }
                    }
                }
            }
            let current = option["currentValue"].clone();
            if (kind == "boolean" && !current.is_boolean())
                || (kind == "select"
                    && current
                        .as_str()
                        .is_none_or(|v| v.len() > 256 || v.chars().any(char::is_control)))
            {
                return Err(Error::invalid_params());
            }
            result.push(AgentConfigOption {
                id: text(option, "id", 256)?,
                name: text(option, "name", 256)?,
                description: text(option, "description", 2048)?,
                category: text(option, "category", 128)?,
                kind,
                current_value: current,
                options: choices,
            });
        }
    }
    // Older adapters advertise modes separately. Prefer the config option when present.
    if !modes.is_null()
        && !result
            .iter()
            .any(|v| v.category == "mode" || v.id == "mode")
    {
        let mut choices = Vec::new();
        for item in modes["availableModes"].as_array().into_iter().flatten() {
            choices.push(AgentConfigChoice {
                value: text(item, "id", 256)?,
                name: text(item, "name", 256)?,
                description: text(item, "description", 2048)?,
                group: String::new(),
            });
        }
        if choices.len() > 256 {
            return Err(Error::invalid_params());
        }
        result.push(AgentConfigOption {
            id: "mode".into(),
            name: "Mode".into(),
            description: "Agent permissions and operating mode".into(),
            category: "mode".into(),
            kind: "mode".into(),
            current_value: Value::String(text(modes, "currentModeId", 256)?),
            options: choices,
        });
    }
    if result.iter().any(|v| v.id.is_empty())
        || result
            .iter()
            .enumerate()
            .any(|(i, v)| result[..i].iter().any(|p| p.id == v.id))
        || serde_json::to_vec(&result)
            .map_err(|_| Error::internal_error())?
            .len()
            > 192 * 1024
    {
        return Err(Error::invalid_params());
    }
    Ok(result)
}

pub(crate) fn accepts(options: &[AgentConfigOption], command: &AgentConfigure) -> bool {
    options
        .iter()
        .find(|v| v.id == command.config_id)
        .is_some_and(|option| {
            if option.kind == "boolean" {
                command.value.is_boolean()
            } else {
                command
                    .value
                    .as_str()
                    .is_some_and(|value| option.options.iter().any(|v| v.value == value))
            }
        })
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn grouped_models_keep_descriptions_and_modes_without_provider_metadata() {
        let options = json!([{"id":"model", "name":"Model", "type":"select", "category":"model", "currentValue":"one", "_meta":{"private":"discard"}, "options":[{"group":"provider", "name":"Provider", "options":[{"value":"one", "name":"One", "description":"First line\nSecond line"}]}]}]);
        let modes = json!({"currentModeId":"ask", "availableModes":[{"id":"ask", "name":"Ask"}]});
        let projected = project(&options, &modes).unwrap();
        assert_eq!(projected.len(), 2);
        assert_eq!(projected[0].options[0].group, "Provider");
        assert!(projected[0].options[0].description.contains('\n'));
        assert!(
            !serde_json::to_string(&projected)
                .unwrap()
                .contains("private")
        );
        assert_eq!(projected[1].kind, "mode");
    }

    #[test]
    fn oversized_or_duplicate_controls_are_rejected() {
        let option = json!({"id":"speed", "name":"Speed", "type":"select", "currentValue":"standard", "options":[{"value":"standard", "name":"Standard"}]});
        assert!(project(&json!([option.clone(), option.clone()]), &Value::Null).is_err());
        let mut oversized = option;
        oversized["options"] = json!(vec![json!({"value":"one", "name":"One"}); 257]);
        assert!(project(&json!([oversized]), &Value::Null).is_err());
    }
}
