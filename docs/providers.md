# Providers

A provider is an endpoint plus a wire format. Pijota ships 22; you can add your own.

## Authenticating

Three ways, checked in this order for the provider actually in use:

1. `--api-key` on the command line (that run, that provider)
2. the provider's environment variable
3. `~/.pijota/auth.json`, written by `pj login`

```bash
pj login                    # pick a provider interactively
pj login anthropic          # OAuth if the provider supports it, else prompts for a key
pj logout anthropic         # forget it
```

`pj auth print-api-key --provider anthropic` prints a stored credential for another tool to
borrow; `print-bearer-token` prints an OAuth access token.

## Built in

| Id | Name | Wire format | Credential |
|---|---|---|---|
| `anthropic` | Anthropic | anthropic | `ANTHROPIC_API_KEY` |
| `openai` | OpenAI | openai | `OPENAI_API_KEY` |
| `openai-codex` | OpenAI Codex | openai | OAuth (`pj login`) |
| `google` | Google AI | google | `GEMINI_API_KEY`, `GOOGLE_API_KEY` |
| `deepseek` | DeepSeek | openai | `DEEPSEEK_API_KEY` |
| `groq` | Groq | openai | `GROQ_API_KEY` |
| `xai` | xAI | openai | `XAI_API_KEY` |
| `mistral` | Mistral | openai | `MISTRAL_API_KEY` |
| `together` | Together AI | openai | `TOGETHER_API_KEY` |
| `openrouter` | OpenRouter | openai | `OPENROUTER_API_KEY` |
| `cerebras` | Cerebras | openai | `CEREBRAS_API_KEY` |
| `fireworks` | Fireworks AI | openai | `FIREWORKS_API_KEY` |
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

Three wire formats: `openai`, `anthropic`, `google`. Anything OpenAI-compatible needs no code.

## Custom providers

Add entries under `providers` in [settings](settings.md). Local servers, gateways and
self-hosted endpoints all fit here.

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
| `id` | yes | name used by `--provider` and `provider/model` |
| `apiType` | yes | `openai`, `anthropic`, or `google` |
| `baseUrl` | yes | endpoint root |
| `displayName` | no | label in pickers |
| `defaultModel` | no | model when only the provider is given |
| `env` | no | environment variables to read a key from |
| `headers` | no | extra request headers |
| `thinkingFormat` | no | how to toggle reasoning; see below |
| `oauth` | no | device-code OAuth: `deviceEndpoint`, `tokenEndpoint`, `oauthTokenEndpoint`, `clientId`, `scope` |

Bad entries are reported as warnings at startup, not as a crash.

### thinkingFormat

OpenAI-compatible runtimes disagree about how to switch reasoning on and off. This picks the
request parameters pijota sends.

| Value | Sends |
|---|---|
| unset | nothing; the model decides |
| `qwen` | `enable_thinking` |
| `qwen-chat-template` | `enable_thinking` plus `chat_template_kwargs.enable_thinking` |

A provider that cannot express "do not think" will think when it feels like it, whatever
`--thinking off` says.

## Timeouts and proxies

| Variable | Property | Default |
|---|---|---|
| `PIJOTA_LLM_CONNECT_TIMEOUT` | `pijota.llm.connect-timeout` | seconds to connect to a provider |
| `PIJOTA_LLM_REQUEST_TIMEOUT` | `pijota.llm.request-timeout` | seconds for a whole request |
| `PIJOTA_CONNECT_TIMEOUT` | `pijota.connect-timeout` | `10` seconds, everything else |
| `PIJOTA_PROXY` | `pijota.proxy` | HTTP proxy for provider traffic |
