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
}

func TestMergeXaiNonInteractive(t *testing.T) {
	cfg := defaultConfig()
	mergeNonInteractive(cfg, "xai", "xai-key", "", "", "", "eve")
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
