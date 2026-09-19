package main

import (
	"context"
	"strings"
	"testing"
)

type fakeRunner map[string]string

func (f fakeRunner) Run(ctx context.Context, name string, args ...string) ([]byte, error) {
	key := name + " " + strings.Join(args, " ")
	return []byte(f[key]), nil
}

func TestSelectDeviceRejectsUnauthorized(t *testing.T) {
	old := runner
	defer func() { runner = old }()
	runner = fakeRunner{"adb devices": "List of devices attached\nabc123\tunauthorized\n"}

	_, err := selectDevice("")
	if err == nil || !strings.Contains(err.Error(), "authorized") {
		t.Fatalf("expected unauthorized error, got %v", err)
	}
}

func TestNormalizeFlatConfigToProviderSchema(t *testing.T) {
	cfg := map[string]any{
		"apiKey": "gemini-key",
		"model":  "gemini-3.1-flash-live-preview",
		"voice":  "Kore",
	}
	normalizeConfig(cfg)

	providers := asMap(cfg["providers"])
	agents := asMap(cfg["agents"])
	gemini := asMap(providers["gemini"])
	voice := asMap(agents["voice"])
	if gemini["apiKey"] != "gemini-key" {
		t.Fatalf("gemini key not migrated: %#v", gemini)
	}
	if voice["provider"] != "gemini" || voice["voice"] != "Kore" {
		t.Fatalf("voice role not normalized: %#v", voice)
	}
	if voice["model"] != defaultGeminiLiveModel {
		t.Fatalf("legacy gemini live model not upgraded: %#v", voice)
	}
	if voice["thinking"] != false || voice["thinkingLevel"] != defaultThinkingLevel {
		t.Fatalf("thinking defaults missing: %#v", voice)
	}
}

func TestMergeXaiNonInteractive(t *testing.T) {
	cfg := defaultConfig()
	mergeNonInteractive(cfg, "xai", "xai-key", "", "", "", "", "eve", "")
	providers := asMap(cfg["providers"])
	agents := asMap(cfg["agents"])
	xai := asMap(providers["xai"])
	voice := asMap(agents["voice"])
	if xai["apiKey"] != "xai-key" {
		t.Fatalf("xai key missing: %#v", xai)
	}
	if voice["provider"] != "xai" || voice["model"] != "grok-voice-think-fast-1.0" {
		t.Fatalf("xai voice defaults missing: %#v", voice)
	}
}

func TestNormalizeUpgradesLegacyXaiVoiceModel(t *testing.T) {
	cfg := map[string]any{
		"providers": map[string]any{
			"xai": map[string]any{"apiKey": "xai-key"},
		},
		"agents": map[string]any{
			"voice": map[string]any{
				"provider": "xai",
				"model":    "grok-voice-fast-1.0",
				"voice":    "eve",
			},
		},
	}
	normalizeConfig(cfg)

	voice := asMap(asMap(cfg["agents"])["voice"])
	if voice["model"] != "grok-voice-think-fast-1.0" {
		t.Fatalf("legacy xai model not upgraded: %#v", voice)
	}
}

func TestNormalizeUpgradesLegacyGeminiLiveModel(t *testing.T) {
	cfg := map[string]any{
		"agents": map[string]any{
			"voice": map[string]any{
				"provider": "gemini",
				"model":    "gemini-3.1-flash-live-preview",
				"voice":    "Kore",
			},
		},
	}
	normalizeConfig(cfg)

	voice := asMap(asMap(cfg["agents"])["voice"])
	if voice["model"] != "gemini-3.8-live" {
		t.Fatalf("legacy gemini live model not upgraded: %#v", voice)
	}
}

func TestNormalizeKeepsCustomGeminiLiveModel(t *testing.T) {
	cfg := map[string]any{
		"agents": map[string]any{
			"voice": map[string]any{
				"provider":      "gemini",
				"model":         "gemini-3.8-live-extended-thinking",
				"thinking":      true,
				"thinkingLevel": "HIGH",
			},
		},
	}
	normalizeConfig(cfg)

	voice := asMap(asMap(cfg["agents"])["voice"])
	if voice["model"] != "gemini-3.8-live-extended-thinking" {
		t.Fatalf("custom live model was replaced: %#v", voice)
	}
	if voice["thinking"] != true || voice["thinkingLevel"] != "high" {
		t.Fatalf("thinking settings not preserved/normalized: %#v", voice)
	}
}

func TestNormalizeMovesLiveModelsOffReasoningRoles(t *testing.T) {
	cfg := map[string]any{
		"agents": map[string]any{
			"expert": map[string]any{"provider": "gemini", "model": "gemini-3.1-flash-live-preview"},
			"memory": map[string]any{"provider": "gemini", "model": "gemini-3.1-pro-preview"},
		},
	}
	normalizeConfig(cfg)

	agents := asMap(cfg["agents"])
	if asMap(agents["expert"])["model"] != defaultReasoningModel {
		t.Fatalf("live model left on expert role: %#v", agents["expert"])
	}
	if asMap(agents["memory"])["model"] != "gemini-3.1-pro-preview" {
		t.Fatalf("reasoning model on memory role was changed: %#v", agents["memory"])
	}
	if asMap(agents["scheduler"])["model"] != defaultReasoningModel {
		t.Fatalf("scheduler role default missing: %#v", agents["scheduler"])
	}
}

func TestMergeNonInteractiveThinkingLevel(t *testing.T) {
	cfg := defaultConfig()
	mergeNonInteractive(cfg, "gemini", "gemini-key", "", "", "", "gemini-3.8-live-extended-thinking", "", "Medium")
	voice := asMap(asMap(cfg["agents"])["voice"])
	if voice["thinking"] != true || voice["thinkingLevel"] != "medium" {
		t.Fatalf("thinking level flag not applied: %#v", voice)
	}

	mergeNonInteractive(cfg, "", "", "", "", "", "", "", "off")
	voice = asMap(asMap(cfg["agents"])["voice"])
	if voice["thinking"] != false || voice["thinkingLevel"] != "medium" {
		t.Fatalf("thinking off flag not applied (level should be kept): %#v", voice)
	}
}

func TestValidThinkingLevelFlag(t *testing.T) {
	for _, ok := range []string{"off", "minimal", "low", "Medium", "high"} {
		if !validThinkingLevelFlag(ok) {
			t.Fatalf("%q should be valid", ok)
		}
	}
	for _, bad := range []string{"", "max", "true"} {
		if validThinkingLevelFlag(bad) {
			t.Fatalf("%q should be invalid", bad)
		}
	}
}

func TestNormalizeAnthropicProviderAndFableDefaults(t *testing.T) {
	cfg := map[string]any{
		"anthropicApiKey": "ant-key",
		"agents": map[string]any{
			"chat": map[string]any{"provider": "claude", "model": ""},
		},
	}
	normalizeConfig(cfg)

	providers := asMap(cfg["providers"])
	if asMap(providers["anthropic"])["apiKey"] != "ant-key" {
		t.Fatalf("anthropic key not migrated: %#v", providers)
	}
	agents := asMap(cfg["agents"])
	chat := asMap(agents["chat"])
	if chat["provider"] != "anthropic" || chat["model"] != defaultAnthropicModel {
		t.Fatalf("chat role not normalized for anthropic: %#v", chat)
	}
	fable := asMap(agents["fable"])
	if fable["provider"] != "anthropic" || fable["model"] != defaultFableModel || fable["effort"] != defaultFableEffort {
		t.Fatalf("fable defaults missing: %#v", fable)
	}
}

func TestNormalizeReasoningModelFamilyMismatch(t *testing.T) {
	cfg := map[string]any{
		"agents": map[string]any{
			"expert": map[string]any{"provider": "xai", "model": "gemini-3.1-pro-preview"},
			"fable":  map[string]any{"model": "gpt-9", "effort": "MAX"},
		},
	}
	normalizeConfig(cfg)

	agents := asMap(cfg["agents"])
	if asMap(agents["expert"])["model"] != defaultXaiTextModel {
		t.Fatalf("mismatched family not replaced: %#v", agents["expert"])
	}
	fable := asMap(agents["fable"])
	if fable["model"] != defaultFableModel || fable["effort"] != defaultFableEffort {
		t.Fatalf("invalid fable settings not reset: %#v", fable)
	}
}

func TestMergeNonInteractiveAnthropicKey(t *testing.T) {
	cfg := defaultConfig()
	mergeNonInteractive(cfg, "", "", "", "", "ant-key", "", "", "")
	if asMap(asMap(cfg["providers"])["anthropic"])["apiKey"] != "ant-key" {
		t.Fatalf("anthropic key flag not applied: %#v", cfg["providers"])
	}
}
