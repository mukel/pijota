# Providers

An endpoint plus a wire format. 22 built in; add your own.

## Credentials

Checked in order, for the provider in use:

1. `--api-key`
2. the provider's environment variable
3. `~/.pijota/auth.json`, written by `pj login`

```bash
pj login                    # pick a provider
pj login anthropic          # OAuth if supported, else prompts for a key
pj logout anthropic
```

## Built in

| Id | Name | Wire format | Credential |
|---|---|---|---|
| `anthropic` | Anthropic | anthropic | `ANTHROPIC_API_KEY` |
| `openai` | OpenAI | openai | `OPENAI_API_KEY` |
| `openai-codex` | OpenAI Codex | openai | OAuth |
| `google` | Google AI | google | `GEMINI_API_KEY`, `GOOGLE_API_KEY` |
| `deepseek` | DeepSeek | openai | `DEEPSEEK_API_KEY` |
| `groq` | Groq | openai | `GROQ_API_KEY` |
| `xai` | xAI | openai | `XAI_API_KEY` |
| `mistral` | Mistral | openai | `MISTRAL_API_KEY` |
| `together` | Together AI | openai | `TOGETHER_API_KEY` |
| `openrouter` | OpenRouter | openai | `OPENROUTER_API_KEY` |
| `cerebras` | Cerebras | openai | `CEREBRAS_API_KEY` |
| `fireworks` | Fireworks AI | openai | `FIREWORKS_API_KEY` |
| `baseten` | Baseten | openai | `BASETEN_API_KEY` |
| `kimi-coding` | Kimi For Coding | anthropic | `KIMI_API_KEY` |
| `moonshotai` | Moonshot AI | openai | `MOONSHOT_API_KEY` |
| `moonshotai-cn` | Moonshot AI China | openai | `MOONSHOT_API_KEY` |
| `zai` | Z.AI | openai | `ZAI_API_KEY` |
| `minimax` | MiniMax | anthropic | `MINIMAX_API_KEY` |
| `huggingface` | Hugging Face | openai | `HF_TOKEN` |
| `nvidia` | NVIDIA NIM | openai | `NVIDIA_API_KEY` |
| `xiaomi-token-plan-cn` | Xiaomi MiMo (China) | openai | `XIAOMI_TOKEN_PLAN_CN_API_KEY` |
| `xiaomi-token-plan-ams` | Xiaomi MiMo (Amsterdam) | openai | `XIAOMI_TOKEN_PLAN_AMS_API_KEY` |
| `xiaomi-token-plan-sgp` | Xiaomi MiMo (Singapore) | openai | `XIAOMI_TOKEN_PLAN_SGP_API_KEY` |

Anything OpenAI-compatible needs no code, only an entry.

## Custom providers

Under `providers` in [settings](settings.md). Bad entries warn at startup.

```json
{
  "providers": [
    {
      "id": "local",
      "displayName": "llama.cpp",
      "apiType": "openai",
      "baseUrl": "http://localhost:8080/v1",
      "defaultModel": "qwen3-coder",
      "env": ["LOCAL_API_KEY"],
      "headers": { "X-Tenant": "dev" },
      "thinkingFormat": "qwen"
    }
  ]
}
```

| Field | Required | Meaning |
|---|---|---|
| `id` | yes | name for `--provider` and `provider/model` |
| `apiType` | yes | `openai`, `anthropic`, `google` |
| `baseUrl` | yes | endpoint root |
| `displayName` | no | label in pickers |
| `defaultModel` | no | model when only the provider is given |
| `env` | no | variables to read a key from |
| `headers` | no | extra request headers |
| `thinkingFormat` | no | reasoning parameters, below |
| `oauth` | no | device code: `deviceEndpoint`, `tokenEndpoint`, `oauthTokenEndpoint`, `clientId`, `scope` |

### thinkingFormat

| Value | Sends |
|---|---|
| unset | nothing |
| `qwen` | `enable_thinking` |
| `qwen-chat-template` | `enable_thinking` plus `chat_template_kwargs.enable_thinking` |

Unset means `--thinking off` cannot stop the model reasoning: nothing is sent and the model
decides.

## Timeouts and proxy

| Variable | Property | Is |
|---|---|---|
| `PIJOTA_LLM_CONNECT_TIMEOUT` | `pijota.llm.connect-timeout` | connect seconds, provider calls |
| `PIJOTA_LLM_REQUEST_TIMEOUT` | `pijota.llm.request-timeout` | whole-request seconds |
| `PIJOTA_CONNECT_TIMEOUT` | `pijota.connect-timeout` | connect seconds, everything else (10) |
| `PIJOTA_PROXY` | `pijota.proxy` | HTTP proxy |
