package main

import (
	"archive/zip"
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"log"
	"mime"
	"net"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"sort"
	"strings"
	"time"
)

const (
	appPackage     = "com.pocketdaemon.pocket_daemon"
	deviceRoot     = "/sdcard/PocketDaemon"
	configPath     = deviceRoot + "/config.json"
	callPromptPath = deviceRoot + "/CALL_PROMPT.md"
	soulPath       = deviceRoot + "/memory/SOUL.md"
	memoryPath     = deviceRoot + "/memory/MEMORY.md"
	indexPath      = deviceRoot + "/memory/INDEX.md"
	tasksPath      = deviceRoot + "/scheduled_tasks.json"
)

type commandRunner interface {
	Run(ctx context.Context, name string, args ...string) ([]byte, error)
}

type execRunner struct{}

func (execRunner) Run(ctx context.Context, name string, args ...string) ([]byte, error) {
	cmd := exec.CommandContext(ctx, name, args...)
	var out bytes.Buffer
	cmd.Stdout = &out
	cmd.Stderr = &out
	err := cmd.Run()
	return out.Bytes(), err
}

var runner commandRunner = execRunner{}

type adbDevice struct {
	Serial string
	State  string
}

type setupState struct {
	Config          map[string]any    `json:"config"`
	CallPrompt      string            `json:"callPrompt"`
	Soul            string            `json:"soul"`
	TrustedContacts []any             `json:"trustedContacts"`
	ScheduledTasks  string            `json:"scheduledTasks"`
	MemoryFiles     map[string]string `json:"memoryFiles"`
	Skills          map[string]string `json:"skills"`
	DeviceSerial    string            `json:"deviceSerial"`
	Diagnostics     []string          `json:"diagnostics"`
}

type setupPayload struct {
	Config          map[string]any    `json:"config"`
	CallPrompt      string            `json:"callPrompt"`
	Soul            string            `json:"soul"`
	TrustedContacts []any             `json:"trustedContacts"`
	ScheduledTasks  string            `json:"scheduledTasks"`
	MemoryFiles     map[string]string `json:"memoryFiles"`
	Skills          map[string]string `json:"skills"`
}

func main() {
	log.SetFlags(0)
	if len(os.Args) < 2 {
		usage()
		os.Exit(2)
	}

	var err error
	switch os.Args[1] {
	case "doctor":
		err = runDoctor(os.Args[2:])
	case "verify":
		err = runVerify(os.Args[2:])
	case "install":
		err = runInstall(os.Args[2:])
	case "setup":
		err = runSetup(os.Args[2:])
	case "backup":
		err = runBackup(os.Args[2:])
	case "restore":
		err = runRestore(os.Args[2:])
	default:
		usage()
		err = fmt.Errorf("unknown command: %s", os.Args[1])
	}
	if err != nil {
		log.Fatal(err)
	}
}

func usage() {
	fmt.Println(`PocketDaemon USB installer and configuration tool

Usage:
  pocketdaemonctl doctor
  pocketdaemonctl install [--module-zip PocketDaemon-magisk.zip] [--no-setup]
  pocketdaemonctl setup [--provider gemini|xai --api-key KEY --model MODEL --voice VOICE --apply]
  pocketdaemonctl verify
  pocketdaemonctl backup [--out pocketdaemon-backup.zip]
  pocketdaemonctl restore backup.zip`)
}

func runDoctor(args []string) error {
	fs := flag.NewFlagSet("doctor", flag.ExitOnError)
	serial := fs.String("serial", "", "ADB device serial")
	_ = fs.Parse(args)

	if err := adbVersion(); err != nil {
		return err
	}
	device, err := selectDevice(*serial)
	if err != nil {
		return err
	}
	fmt.Printf("device: %s\n", device.Serial)
	checkShell(device.Serial, "android", "getprop ro.build.version.release")
	checkShell(device.Serial, "sdk", "getprop ro.build.version.sdk")
	checkShell(device.Serial, "root", "su -c id")
	checkShell(device.Serial, "magisk", "su -c magisk -v")
	checkPackage(device.Serial)
	checkShell(device.Serial, "config", "test -f "+configPath+" && echo present || echo missing")
	return nil
}

func runVerify(args []string) error {
	fs := flag.NewFlagSet("verify", flag.ExitOnError)
	serial := fs.String("serial", "", "ADB device serial")
	_ = fs.Parse(args)

	device, err := selectDevice(*serial)
	if err != nil {
		return err
	}
	fmt.Printf("device: %s\n", device.Serial)
	checkPackage(device.Serial)
	checkShell(device.Serial, "storage", "test -d "+deviceRoot+" && echo present || echo missing")
	checkShell(device.Serial, "config", "test -s "+configPath+" && echo present || echo missing")
	checkShell(device.Serial, "provider", "grep -q '\"providers\"' "+configPath+" && echo schema-ok || echo legacy-or-missing")
	checkShell(device.Serial, "permissions", "dumpsys package "+appPackage+" | grep -E 'granted=true|grantedPermissions' | head -n 20")
	return nil
}

func runInstall(args []string) error {
	fs := flag.NewFlagSet("install", flag.ExitOnError)
	serial := fs.String("serial", "", "ADB device serial")
	moduleZip := fs.String("module-zip", "", "Magisk module zip to install")
	noSetup := fs.Bool("no-setup", false, "do not launch setup after installation")
	_ = fs.Parse(args)

	device, err := selectDevice(*serial)
	if err != nil {
		return err
	}
	if *moduleZip == "" {
		if found := findDefaultModuleZip(); found != "" {
			*moduleZip = found
		}
	}
	if *moduleZip == "" {
		return errors.New("missing module zip; run flutter build apk --release and ./build_magisk.ps1, or pass --module-zip")
	}
	if _, err := os.Stat(*moduleZip); err != nil {
		return fmt.Errorf("module zip not found: %w", err)
	}

	fmt.Printf("device: %s\n", device.Serial)
	if _, err := adbShell(device.Serial, "su -c id"); err != nil {
		return errors.New("root is not available through adb shell; install the module manually in the Magisk app")
	}

	remote := "/sdcard/Download/" + filepath.Base(*moduleZip)
	fmt.Printf("pushing module: %s\n", remote)
	if _, err := adb(device.Serial, "push", *moduleZip, remote); err != nil {
		return fmt.Errorf("adb push failed: %w", err)
	}

	fmt.Println("attempting Magisk command-line install")
	if out, err := adbShell(device.Serial, "su -c magisk --install-module "+remote); err != nil {
		fmt.Printf("magisk CLI install failed:\n%s\n", strings.TrimSpace(string(out)))
		fmt.Println("Open the Magisk app, choose Modules > Install from storage, select the zip in Download, then reboot.")
		return nil
	}
	fmt.Println("module installed; rebooting")
	_, _ = adb(device.Serial, "reboot")
	waitForDevice(device.Serial, 2*time.Minute)
	_ = runVerify([]string{"--serial", device.Serial})
	if !*noSetup {
		return runSetup([]string{"--serial", device.Serial})
	}
	return nil
}

func runSetup(args []string) error {
	fs := flag.NewFlagSet("setup", flag.ExitOnError)
	serial := fs.String("serial", "", "ADB device serial")
	provider := fs.String("provider", "", "initial voice provider: gemini or xai")
	apiKey := fs.String("api-key", "", "API key for selected provider")
	geminiKey := fs.String("gemini-api-key", "", "Gemini API key")
	xaiKey := fs.String("xai-api-key", "", "xAI API key")
	anthropicKey := fs.String("anthropic-api-key", "", "Anthropic API key (Claude reasoning and the ask_fable tool)")
	model := fs.String("model", "", "voice model")
	voice := fs.String("voice", "", "voice name")
	thinkingLevel := fs.String("thinking-level", "", "Gemini live thinking: off, minimal, low, medium, or high (gemini-3.8-live does not accept a level)")
	apply := fs.Bool("apply", false, "apply flags without opening the browser UI")
	addr := fs.String("addr", "127.0.0.1:0", "localhost listen address")
	noBrowser := fs.Bool("no-browser", false, "print URL instead of opening browser")
	_ = fs.Parse(args)

	device, err := selectDevice(*serial)
	if err != nil {
		return err
	}
	state, err := pullSetupState(device.Serial)
	if err != nil {
		return err
	}
	if *thinkingLevel != "" && !validThinkingLevelFlag(*thinkingLevel) {
		return fmt.Errorf("invalid --thinking-level %q: use off, minimal, low, medium, or high", *thinkingLevel)
	}
	mergeNonInteractive(state.Config, *provider, *apiKey, *geminiKey, *xaiKey, *anthropicKey, *model, *voice, *thinkingLevel)
	if *apply {
		if err := pushSetupState(device.Serial, setupPayload{
			Config:          state.Config,
			CallPrompt:      state.CallPrompt,
			Soul:            state.Soul,
			TrustedContacts: state.TrustedContacts,
			ScheduledTasks:  state.ScheduledTasks,
			MemoryFiles:     state.MemoryFiles,
			Skills:          state.Skills,
		}); err != nil {
			return err
		}
		restartApp(device.Serial)
		fmt.Println("configuration applied")
		return nil
	}

	ln, err := net.Listen("tcp", *addr)
	if err != nil {
		return err
	}
	defer ln.Close()

	mux := http.NewServeMux()
	server := &setupServer{serial: device.Serial, state: state}
	mux.HandleFunc("/", server.index)
	mux.HandleFunc("/api/state", server.getState)
	mux.HandleFunc("/api/save", server.save)

	url := "http://" + ln.Addr().String()
	fmt.Printf("setup UI: %s\n", url)
	if !*noBrowser {
		_ = openBrowser(url)
	}
	return http.Serve(ln, mux)
}

func runBackup(args []string) error {
	fs := flag.NewFlagSet("backup", flag.ExitOnError)
	serial := fs.String("serial", "", "ADB device serial")
	out := fs.String("out", "pocketdaemon-backup.zip", "backup zip path")
	_ = fs.Parse(args)

	device, err := selectDevice(*serial)
	if err != nil {
		return err
	}
	tmp, err := os.MkdirTemp("", "pocketdaemon-backup-*")
	if err != nil {
		return err
	}
	defer os.RemoveAll(tmp)

	if _, err := adb(device.Serial, "pull", deviceRoot, tmp); err != nil {
		return fmt.Errorf("adb pull failed: %w", err)
	}
	src := filepath.Join(tmp, "PocketDaemon")
	if err := zipDir(*out, src, "PocketDaemon"); err != nil {
		return err
	}
	fmt.Printf("backup written: %s\n", *out)
	return nil
}

func runRestore(args []string) error {
	fs := flag.NewFlagSet("restore", flag.ExitOnError)
	serial := fs.String("serial", "", "ADB device serial")
	_ = fs.Parse(args)
	if fs.NArg() != 1 {
		return errors.New("restore requires a backup zip")
	}

	device, err := selectDevice(*serial)
	if err != nil {
		return err
	}
	tmp, err := os.MkdirTemp("", "pocketdaemon-restore-*")
	if err != nil {
		return err
	}
	defer os.RemoveAll(tmp)

	if err := unzip(fs.Arg(0), tmp); err != nil {
		return err
	}
	src := filepath.Join(tmp, "PocketDaemon")
	if _, err := os.Stat(src); err != nil {
		return errors.New("backup zip does not contain PocketDaemon/")
	}
	if _, err := adbShell(device.Serial, "mkdir -p "+deviceRoot); err != nil {
		return err
	}
	if _, err := adb(device.Serial, "push", src+string(os.PathSeparator)+".", deviceRoot+"/"); err != nil {
		return err
	}
	restartApp(device.Serial)
	fmt.Println("restore applied")
	return nil
}

type setupServer struct {
	serial string
	state  setupState
}

func (s *setupServer) index(w http.ResponseWriter, r *http.Request) {
	if r.URL.Path != "/" {
		http.NotFound(w, r)
		return
	}
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	_, _ = io.WriteString(w, setupHTML)
}

func (s *setupServer) getState(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, s.state)
}

func (s *setupServer) save(w http.ResponseWriter, r *http.Request) {
	defer r.Body.Close()
	var payload setupPayload
	if err := json.NewDecoder(io.LimitReader(r.Body, 8<<20)).Decode(&payload); err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}
	if err := pushSetupState(s.serial, payload); err != nil {
		http.Error(w, err.Error(), http.StatusBadGateway)
		return
	}
	restartApp(s.serial)
	state, err := pullSetupState(s.serial)
	if err != nil {
		http.Error(w, err.Error(), http.StatusBadGateway)
		return
	}
	s.state = state
	writeJSON(w, map[string]any{"status": "saved", "diagnostics": state.Diagnostics})
}

func pullSetupState(serial string) (setupState, error) {
	state := setupState{
		Config:       defaultConfig(),
		MemoryFiles:  map[string]string{},
		Skills:       map[string]string{},
		DeviceSerial: serial,
	}
	if raw, err := adbCat(serial, configPath); err == nil && strings.TrimSpace(raw) != "" {
		if err := json.Unmarshal([]byte(raw), &state.Config); err != nil {
			state.Diagnostics = append(state.Diagnostics, "config.json is invalid; using defaults")
		}
	}
	normalizeConfig(state.Config)
	state.CallPrompt, _ = adbCat(serial, callPromptPath)
	state.Soul, _ = adbCat(serial, soulPath)
	state.ScheduledTasks, _ = adbCat(serial, tasksPath)
	for _, path := range []string{memoryPath, indexPath} {
		if content, err := adbCat(serial, path); err == nil {
			state.MemoryFiles[strings.TrimPrefix(path, deviceRoot+"/")] = content
		}
	}
	for _, path := range adbFind(serial, deviceRoot+"/memory", "*.md", 2) {
		if path == soulPath || path == memoryPath || path == indexPath {
			continue
		}
		if content, err := adbCat(serial, path); err == nil {
			state.MemoryFiles[strings.TrimPrefix(path, deviceRoot+"/")] = content
		}
	}
	for _, path := range adbFind(serial, deviceRoot+"/skills", "*.md", 4) {
		if content, err := adbCat(serial, path); err == nil {
			state.Skills[strings.TrimPrefix(path, deviceRoot+"/skills/")] = content
		}
	}
	if contacts, ok := state.Config["trustedContacts"].([]any); ok {
		state.TrustedContacts = contacts
	}
	return state, nil
}

func pushSetupState(serial string, payload setupPayload) error {
	normalizeConfig(payload.Config)
	if payload.TrustedContacts != nil {
		payload.Config["trustedContacts"] = payload.TrustedContacts
	}
	if err := adbMkdir(serial, deviceRoot, deviceRoot+"/memory", deviceRoot+"/skills"); err != nil {
		return err
	}
	configBytes, err := json.MarshalIndent(payload.Config, "", "  ")
	if err != nil {
		return err
	}
	if err := adbPushBytes(serial, configPath, append(configBytes, '\n')); err != nil {
		return err
	}
	if err := adbPushBytes(serial, callPromptPath, []byte(payload.CallPrompt)); err != nil {
		return err
	}
	if err := adbPushBytes(serial, soulPath, []byte(payload.Soul)); err != nil {
		return err
	}
	if payload.ScheduledTasks != "" {
		if !json.Valid([]byte(payload.ScheduledTasks)) {
			return errors.New("scheduled_tasks.json is not valid JSON")
		}
		if err := adbPushBytes(serial, tasksPath, []byte(payload.ScheduledTasks)); err != nil {
			return err
		}
	}
	for rel, content := range payload.MemoryFiles {
		if !safeRel(rel) || !strings.HasPrefix(rel, "memory/") {
			return fmt.Errorf("unsafe memory path: %s", rel)
		}
		if err := adbMkdir(serial, deviceRoot+"/"+pathDirSlash(rel)); err != nil {
			return err
		}
		if err := adbPushBytes(serial, deviceRoot+"/"+rel, []byte(content)); err != nil {
			return err
		}
	}
	for rel, content := range payload.Skills {
		if !safeRel(rel) {
			return fmt.Errorf("unsafe skill path: %s", rel)
		}
		full := deviceRoot + "/skills/" + rel
		if err := adbMkdir(serial, deviceRoot+"/skills/"+pathDirSlash(rel)); err != nil {
			return err
		}
		if err := adbPushBytes(serial, full, []byte(content)); err != nil {
			return err
		}
	}
	return nil
}

func mergeNonInteractive(config map[string]any, provider, apiKey, geminiKey, xaiKey, anthropicKey, model, voice, thinkingLevel string) {
	normalizeConfig(config)
	agents := asMap(config["agents"])
	providers := asMap(config["providers"])
	voiceAgent := asMap(agents["voice"])
	if provider != "" {
		provider = normalizeProvider(provider)
		voiceAgent["provider"] = provider
	}
	selected := normalizeProvider(fmt.Sprint(voiceAgent["provider"]))
	if apiKey != "" {
		asMap(providers[selected])["apiKey"] = apiKey
	}
	if geminiKey != "" {
		asMap(providers["gemini"])["apiKey"] = geminiKey
	}
	if xaiKey != "" {
		asMap(providers["xai"])["apiKey"] = xaiKey
	}
	if anthropicKey != "" {
		asMap(providers["anthropic"])["apiKey"] = anthropicKey
	}
	if model != "" {
		voiceAgent["model"] = model
	} else if provider != "" {
		current := fmt.Sprint(voiceAgent["model"])
		if modelNeedsDefault(selected, current) {
			voiceAgent["model"] = defaultModel(selected)
		}
	}
	if voice != "" {
		voiceAgent["voice"] = voice
	}
	if thinkingLevel != "" {
		level := strings.ToLower(strings.TrimSpace(thinkingLevel))
		if level == "off" {
			voiceAgent["thinking"] = false
		} else {
			voiceAgent["thinking"] = true
			voiceAgent["thinkingLevel"] = level
		}
	}
	config["providers"] = providers
	config["agents"] = agents
	normalizeConfig(config)
}

const (
	defaultGeminiLiveModel = "gemini-3.8-live"
	defaultXaiVoiceModel   = "grok-voice-think-fast-1.0"
	defaultReasoningModel  = "gemini-3.1-pro-preview"
	defaultXaiTextModel    = "grok-4.6"
	defaultAnthropicModel  = "claude-opus-5"
	defaultFableModel      = "claude-fable-5-1"
	defaultFableEffort     = "medium"
	defaultThinkingLevel   = "low"
)

// REST roles that may run on any provider with a key.
var reasoningRoles = []string{"chat", "expert", "scheduler", "memory"}

var effortLevels = map[string]bool{"low": true, "medium": true, "high": true}

var modelFamilies = map[string]string{"gemini": "gemini-", "xai": "grok-", "anthropic": "claude-"}

// Older Gemini live models are upgraded to defaultGeminiLiveModel on normalize.
var legacyGeminiLiveModels = map[string]bool{"gemini-3.1-flash-live-preview": true}

var thinkingLevels = map[string]bool{"minimal": true, "low": true, "medium": true, "high": true}

func defaultConfig() map[string]any {
	return map[string]any{
		"providers": map[string]any{
			"gemini":    map[string]any{"apiKey": ""},
			"xai":       map[string]any{"apiKey": ""},
			"anthropic": map[string]any{"apiKey": ""},
		},
		"agents": map[string]any{
			"voice": map[string]any{
				"provider":      "gemini",
				"model":         defaultGeminiLiveModel,
				"voice":         "Kore",
				"thinking":      false,
				"thinkingLevel": defaultThinkingLevel,
			},
			"chat":      map[string]any{"provider": "gemini", "model": defaultReasoningModel},
			"expert":    map[string]any{"provider": "gemini", "model": defaultReasoningModel},
			"scheduler": map[string]any{"provider": "gemini", "model": defaultReasoningModel},
			"memory":    map[string]any{"provider": "gemini", "model": defaultReasoningModel},
			"fable":     map[string]any{"provider": "anthropic", "model": defaultFableModel, "effort": defaultFableEffort},
		},
		"ownerName":   "Phone owner",
		"agentName":   "",
		"agentRole":   "personal AI assistant",
		"answerDelay": float64(2000),
	}
}

func normalizeConfig(config map[string]any) {
	if config == nil {
		return
	}
	providers := asMap(config["providers"])
	agents := asMap(config["agents"])
	if _, ok := providers["gemini"]; !ok {
		providers["gemini"] = map[string]any{"apiKey": stringValue(config["apiKey"])}
	}
	if _, ok := providers["xai"]; !ok {
		providers["xai"] = map[string]any{"apiKey": stringValue(config["xaiApiKey"])}
	}
	if _, ok := providers["anthropic"]; !ok {
		providers["anthropic"] = map[string]any{"apiKey": stringValue(config["anthropicApiKey"])}
	}
	gemini := asMap(providers["gemini"])
	if gemini["apiKey"] == nil || fmt.Sprint(gemini["apiKey"]) == "" {
		gemini["apiKey"] = stringValue(config["apiKey"])
	}
	xai := asMap(providers["xai"])
	if xai["apiKey"] == nil {
		xai["apiKey"] = stringValue(config["xaiApiKey"])
	}
	anthropic := asMap(providers["anthropic"])
	if anthropic["apiKey"] == nil {
		anthropic["apiKey"] = stringValue(config["anthropicApiKey"])
	}
	providers["gemini"] = gemini
	providers["xai"] = xai
	providers["anthropic"] = anthropic

	voice := asMap(agents["voice"])
	provider := normalizeProvider(firstString(voice["provider"], config["voiceProvider"], "gemini"))
	voice["provider"] = provider
	model := firstString(voice["model"], config["model"], defaultModel(provider))
	if modelNeedsDefault(provider, model) {
		model = defaultModel(provider)
	}
	voice["model"] = model
	voice["voice"] = firstString(voice["voice"], config["voice"], defaultVoice(provider))
	// Thinking is opt-in; the app only sends thinkingConfig to Gemini Live when it is enabled.
	voice["thinking"] = boolValue(voice["thinking"])
	level := strings.ToLower(firstString(voice["thinkingLevel"], defaultThinkingLevel))
	if !thinkingLevels[level] {
		level = defaultThinkingLevel
	}
	voice["thinkingLevel"] = level
	agents["voice"] = voice
	for _, role := range reasoningRoles {
		roleCfg := asMap(agents[role])
		rp := normalizeReasoningProvider(firstString(roleCfg["provider"], "gemini"))
		roleCfg["provider"] = rp
		roleCfg["model"] = reasoningModelOrDefault(rp, firstString(roleCfg["model"]))
		agents[role] = roleCfg
	}
	// Fable is always Claude; the model must be a Claude model and effort must be a known level.
	fable := asMap(agents["fable"])
	fable["provider"] = "anthropic"
	fableModel := firstString(fable["model"])
	if !strings.HasPrefix(fableModel, "claude-") {
		fableModel = defaultFableModel
	}
	fable["model"] = fableModel
	fableEffort := strings.ToLower(firstString(fable["effort"]))
	if !effortLevels[fableEffort] {
		fableEffort = defaultFableEffort
	}
	fable["effort"] = fableEffort
	agents["fable"] = fable
	config["providers"] = providers
	config["agents"] = agents
	config["apiKey"] = fmt.Sprint(gemini["apiKey"])
	config["model"] = fmt.Sprint(voice["model"])
	config["voice"] = fmt.Sprint(voice["voice"])
}

func asMap(v any) map[string]any {
	if m, ok := v.(map[string]any); ok {
		return m
	}
	if m, ok := v.(map[any]any); ok {
		out := map[string]any{}
		for k, v := range m {
			out[fmt.Sprint(k)] = v
		}
		return out
	}
	out := map[string]any{}
	return out
}

func firstString(values ...any) string {
	for _, v := range values {
		s := strings.TrimSpace(fmt.Sprint(v))
		if s != "" && s != "<nil>" {
			return s
		}
	}
	return ""
}

func stringValue(v any) string {
	if v == nil {
		return ""
	}
	return strings.TrimSpace(fmt.Sprint(v))
}

func normalizeProvider(provider string) string {
	if strings.EqualFold(strings.TrimSpace(provider), "xai") {
		return "xai"
	}
	return "gemini"
}

func defaultModel(provider string) string {
	if provider == "xai" {
		return defaultXaiVoiceModel
	}
	return defaultGeminiLiveModel
}

func modelNeedsDefault(provider, model string) bool {
	model = strings.TrimSpace(model)
	if model == "" {
		return true
	}
	if provider == "xai" {
		return model == "grok-voice-fast-1.0" || strings.HasPrefix(model, "gemini-")
	}
	return legacyGeminiLiveModels[model] || strings.HasPrefix(model, "grok-voice-")
}

// normalizeReasoningProvider accepts gemini, xai, or anthropic (claude) for REST roles.
func normalizeReasoningProvider(provider string) string {
	switch strings.ToLower(strings.TrimSpace(provider)) {
	case "xai":
		return "xai"
	case "anthropic", "claude":
		return "anthropic"
	}
	return "gemini"
}

func reasoningDefaultModel(provider string) string {
	switch provider {
	case "xai":
		return defaultXaiTextModel
	case "anthropic":
		return defaultAnthropicModel
	}
	return defaultReasoningModel
}

// reasoningModelOrDefault keeps a REST role's model only when it is a text model from the
// provider's own family; live/voice ids and other families fall back to the provider default.
func reasoningModelOrDefault(provider, model string) string {
	model = strings.TrimSpace(model)
	family := modelFamilies[provider]
	if model == "" || strings.Contains(model, "-live") || strings.HasPrefix(model, "grok-voice-") || !strings.HasPrefix(model, family) {
		return reasoningDefaultModel(provider)
	}
	return model
}

func boolValue(v any) bool {
	switch b := v.(type) {
	case bool:
		return b
	case string:
		return strings.EqualFold(strings.TrimSpace(b), "true")
	default:
		return false
	}
}

func validThinkingLevelFlag(v string) bool {
	v = strings.ToLower(strings.TrimSpace(v))
	return v == "off" || thinkingLevels[v]
}

func defaultVoice(provider string) string {
	if provider == "xai" {
		return "eve"
	}
	return "Kore"
}

func adbVersion() error {
	out, err := runner.Run(context.Background(), "adb", "version")
	if err != nil {
		return fmt.Errorf("adb not available: %w\n%s", err, strings.TrimSpace(string(out)))
	}
	fmt.Println(firstLine(string(out)))
	return nil
}

func selectDevice(serial string) (adbDevice, error) {
	devices, err := listDevices()
	if err != nil {
		return adbDevice{}, err
	}
	if serial != "" {
		for _, d := range devices {
			if d.Serial == serial {
				if d.State != "device" {
					return adbDevice{}, fmt.Errorf("device %s is %s", serial, d.State)
				}
				return d, nil
			}
		}
		return adbDevice{}, fmt.Errorf("device %s not found", serial)
	}
	var authorized []adbDevice
	var blocked []adbDevice
	for _, d := range devices {
		if d.State == "device" {
			authorized = append(authorized, d)
		} else {
			blocked = append(blocked, d)
		}
	}
	if len(authorized) == 1 {
		return authorized[0], nil
	}
	if len(authorized) == 0 && len(blocked) > 0 {
		return adbDevice{}, fmt.Errorf("no authorized device; current state: %s", blocked[0].State)
	}
	if len(authorized) == 0 {
		return adbDevice{}, errors.New("no ADB device found; connect the phone and enable USB debugging")
	}
	return adbDevice{}, errors.New("multiple ADB devices found; pass --serial")
}

func listDevices() ([]adbDevice, error) {
	out, err := runner.Run(context.Background(), "adb", "devices")
	if err != nil {
		return nil, fmt.Errorf("adb devices failed: %w", err)
	}
	var devices []adbDevice
	for _, line := range strings.Split(string(out), "\n") {
		line = strings.TrimSpace(line)
		if line == "" || strings.HasPrefix(line, "List of devices") {
			continue
		}
		fields := strings.Fields(line)
		if len(fields) >= 2 {
			devices = append(devices, adbDevice{Serial: fields[0], State: fields[1]})
		}
	}
	return devices, nil
}

func adb(serial string, args ...string) ([]byte, error) {
	all := []string{}
	if serial != "" {
		all = append(all, "-s", serial)
	}
	all = append(all, args...)
	ctx, cancel := context.WithTimeout(context.Background(), 90*time.Second)
	defer cancel()
	return runner.Run(ctx, "adb", all...)
}

func adbShell(serial, command string) ([]byte, error) {
	return adb(serial, "shell", command)
}

func adbCat(serial, path string) (string, error) {
	out, err := adb(serial, "exec-out", "cat", path)
	if err != nil {
		return "", err
	}
	return string(out), nil
}

func adbFind(serial, root, pattern string, maxDepth int) []string {
	cmd := fmt.Sprintf("test -d %[1]s && find %[1]s -maxdepth %d -type f -name '%s' 2>/dev/null", root, maxDepth, pattern)
	out, err := adbShell(serial, cmd)
	if err != nil {
		return nil
	}
	var paths []string
	for _, line := range strings.Split(string(out), "\n") {
		line = strings.TrimSpace(strings.TrimSuffix(line, "\r"))
		if line != "" {
			paths = append(paths, line)
		}
	}
	sort.Strings(paths)
	return paths
}

func adbMkdir(serial string, dirs ...string) error {
	for _, dir := range dirs {
		if _, err := adbShell(serial, "mkdir -p "+dir); err != nil {
			return err
		}
	}
	return nil
}

func adbPushBytes(serial, remote string, data []byte) error {
	tmp, err := os.CreateTemp("", "pocketdaemon-*")
	if err != nil {
		return err
	}
	name := tmp.Name()
	if _, err := tmp.Write(data); err != nil {
		_ = tmp.Close()
		_ = os.Remove(name)
		return err
	}
	_ = tmp.Close()
	defer os.Remove(name)
	if _, err := adb(serial, "push", name, remote); err != nil {
		return fmt.Errorf("push %s failed: %w", remote, err)
	}
	return nil
}

func checkLine(label string, out []byte, err error) {
	status := "ok"
	text := strings.TrimSpace(string(out))
	if err != nil {
		status = "fail"
	}
	if text == "" {
		text = "-"
	}
	fmt.Printf("%-12s %s  %s\n", label+":", status, redact(oneLine(text)))
}

func checkShell(serial, label, command string) {
	out, err := adbShell(serial, command)
	checkLine(label, out, err)
}

func checkPackage(serial string) {
	out, err := adbShell(serial, "pm list packages "+appPackage)
	if err != nil || !strings.Contains(string(out), appPackage) {
		fmt.Printf("%-12s fail  missing\n", "package:")
		return
	}
	fmt.Printf("%-12s ok  installed\n", "package:")
}

func waitForDevice(serial string, timeout time.Duration) {
	deadline := time.Now().Add(timeout)
	for time.Now().Before(deadline) {
		if _, err := adb(serial, "wait-for-device"); err == nil {
			return
		}
		time.Sleep(2 * time.Second)
	}
}

func restartApp(serial string) {
	_, _ = adbShell(serial, "am force-stop "+appPackage)
	_, _ = adbShell(serial, "monkey -p "+appPackage+" 1 >/dev/null 2>&1")
}

func findDefaultModuleZip() string {
	root := repoRoot()
	candidates := []string{
		filepath.Join(root, "PocketDaemon-magisk.zip"),
		filepath.Join(root, "build", "PocketDaemon-magisk.zip"),
	}
	for _, path := range candidates {
		if _, err := os.Stat(path); err == nil {
			return path
		}
	}
	return ""
}

func repoRoot() string {
	wd, _ := os.Getwd()
	for {
		if _, err := os.Stat(filepath.Join(wd, "pubspec.yaml")); err == nil {
			return wd
		}
		next := filepath.Dir(wd)
		if next == wd {
			return wd
		}
		wd = next
	}
}

func zipDir(zipPath, srcDir, rootName string) error {
	out, err := os.Create(zipPath)
	if err != nil {
		return err
	}
	defer out.Close()
	zw := zip.NewWriter(out)
	defer zw.Close()
	return filepath.WalkDir(srcDir, func(path string, d os.DirEntry, err error) error {
		if err != nil {
			return err
		}
		if d.IsDir() {
			return nil
		}
		rel, _ := filepath.Rel(srcDir, path)
		name := filepath.ToSlash(filepath.Join(rootName, rel))
		w, err := zw.Create(name)
		if err != nil {
			return err
		}
		f, err := os.Open(path)
		if err != nil {
			return err
		}
		defer f.Close()
		_, err = io.Copy(w, f)
		return err
	})
}

func unzip(zipPath, dest string) error {
	zr, err := zip.OpenReader(zipPath)
	if err != nil {
		return err
	}
	defer zr.Close()
	cleanDest, _ := filepath.Abs(dest)
	for _, f := range zr.File {
		target := filepath.Join(dest, f.Name)
		abs, _ := filepath.Abs(target)
		if abs != cleanDest && !strings.HasPrefix(abs, cleanDest+string(os.PathSeparator)) {
			return fmt.Errorf("unsafe zip path: %s", f.Name)
		}
		if f.FileInfo().IsDir() {
			if err := os.MkdirAll(target, 0755); err != nil {
				return err
			}
			continue
		}
		if err := os.MkdirAll(filepath.Dir(target), 0755); err != nil {
			return err
		}
		rc, err := f.Open()
		if err != nil {
			return err
		}
		out, err := os.Create(target)
		if err != nil {
			_ = rc.Close()
			return err
		}
		_, copyErr := io.Copy(out, rc)
		_ = rc.Close()
		_ = out.Close()
		if copyErr != nil {
			return copyErr
		}
	}
	return nil
}

func openBrowser(url string) error {
	var cmd *exec.Cmd
	switch runtime.GOOS {
	case "windows":
		cmd = exec.Command("rundll32", "url.dll,FileProtocolHandler", url)
	case "darwin":
		cmd = exec.Command("open", url)
	default:
		cmd = exec.Command("xdg-open", url)
	}
	return cmd.Start()
}

func writeJSON(w http.ResponseWriter, v any) {
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(v)
}

func safeRel(path string) bool {
	return path != "" &&
		!strings.HasPrefix(path, "/") &&
		!strings.Contains(path, "\\") &&
		!strings.Contains(path, "..")
}

func pathDirSlash(rel string) string {
	dir := filepath.ToSlash(filepath.Dir(rel))
	if dir == "." {
		return ""
	}
	return dir
}

func redact(text string) string {
	words := strings.Fields(text)
	for i, w := range words {
		if len(w) > 24 && (strings.Contains(strings.ToLower(w), "key") || strings.HasPrefix(w, "AIza") || strings.HasPrefix(w, "xai-")) {
			words[i] = w[:6] + "..."
		}
	}
	return strings.Join(words, " ")
}

func oneLine(s string) string {
	s = strings.ReplaceAll(s, "\r", " ")
	s = strings.ReplaceAll(s, "\n", " ")
	if len(s) > 240 {
		return s[:240] + "..."
	}
	return s
}

func firstLine(s string) string {
	for _, line := range strings.Split(s, "\n") {
		line = strings.TrimSpace(line)
		if line != "" {
			return line
		}
	}
	return ""
}

func init() {
	_ = mime.AddExtensionType(".md", "text/markdown; charset=utf-8")
}

const setupHTML = `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>PocketDaemon Setup</title>
<style>
:root{font-family:Inter,system-ui,Segoe UI,sans-serif;color:#16201b;background:#f6f8f5}
body{margin:0}
header{position:sticky;top:0;z-index:1;display:flex;justify-content:space-between;gap:16px;align-items:center;padding:16px 22px;background:#ffffffd9;border-bottom:1px solid #d9dfd7;backdrop-filter:blur(12px)}
h1{font-size:20px;margin:0}main{max-width:1180px;margin:0 auto;padding:22px;display:grid;grid-template-columns:minmax(260px,360px) 1fr;gap:16px}
section{background:#fff;border:1px solid #d9dfd7;border-radius:8px;padding:16px}h2{font-size:15px;margin:0 0 14px}
label{display:block;font-size:12px;font-weight:700;color:#4a5650;margin:12px 0 6px}
input,select,textarea{width:100%;box-sizing:border-box;border:1px solid #c8d0c6;border-radius:6px;background:#fbfcfa;color:#16201b;font:inherit;padding:9px}
textarea{min-height:132px;resize:vertical;font-family:ui-monospace,SFMono-Regular,Consolas,monospace;font-size:13px}
.row{display:grid;grid-template-columns:1fr 1fr;gap:10px}
.help{font-size:12px;color:#8a8a8a;margin:4px 0 0}.tabs{display:flex;gap:6px;flex-wrap:wrap;margin-bottom:12px}
button{border:1px solid #7c8b7f;background:#fff;color:#16201b;border-radius:6px;padding:9px 12px;font-weight:700;cursor:pointer}
button.primary{background:#196b4f;border-color:#196b4f;color:white}.tabs button.active{background:#e3eee7;border-color:#196b4f}
.warn{background:#fff7db;border:1px solid #ead78b;border-radius:6px;padding:10px;color:#51420b;font-size:13px;margin:8px 0}
.status{font-size:13px;color:#4a5650}.diag{white-space:pre-wrap;font-size:12px;color:#4a5650}.wide{grid-column:1/-1}
@media(max-width:900px){main{grid-template-columns:1fr}.row{grid-template-columns:1fr}}
</style>
</head>
<body>
<header><h1>PocketDaemon USB Setup</h1><div><span id="status" class="status">Loading</span> <button class="primary" onclick="save()">Save to phone</button></div></header>
<main>
<section>
<h2>Voice Provider</h2>
<label>Provider</label><select id="provider" onchange="providerChanged()"><option value="gemini">Gemini</option><option value="xai">xAI</option></select>
<label>Gemini API Key</label><input id="geminiKey" type="password" autocomplete="off">
<label>xAI API Key</label><input id="xaiKey" type="password" autocomplete="off">
<label>Anthropic API Key</label><input id="anthropicKey" type="password" autocomplete="off">
<div class="row"><div><label>Model</label><input id="model" placeholder="gemini-3.8-live" oninput="modelChanged()"></div><div><label>Voice</label><select id="voice"></select></div></div>
<div class="row" id="thinkingRow"><div><label>Thinking</label><select id="thinking"><option value="false">Off (default)</option><option value="true">On</option></select></div><div><label>Thinking Level</label><select id="thinkingLevel"><option value="minimal">minimal</option><option value="low">low</option><option value="medium">medium</option><option value="high">high</option></select></div></div>
<p class="help" id="thinkingHelp">Not supported on gemini-3.8-live (ignored). Use gemini-3.8-live-extended-thinking for a configurable thinking level.</p>
<h2 style="margin-top:18px">Reasoning</h2>
<div class="row"><div><label>Provider (chat, expert, scheduler, memory)</label><select id="reasoningProvider" onchange="reasoningProviderChanged()"><option value="gemini">Gemini</option><option value="xai">xAI</option><option value="anthropic">Claude</option></select></div><div><label>Model</label><input id="reasoningModel" placeholder="gemini-3.1-pro-preview"></div></div>
<div class="row"><div><label>Fable Model</label><input id="fableModel" placeholder="claude-fable-5-1"></div><div><label>Fable Effort</label><select id="fableEffort"><option value="low">low</option><option value="medium">medium</option><option value="high">high</option></select></div></div>
<p class="help">Fable (the ask_fable tool) always uses Claude with web search and needs the Anthropic key. Reasoning roles fall back to any provider that has a key.</p>
<h2 style="margin-top:18px">Identity</h2>
<label>Owner Name</label><input id="ownerName">
<label>Agent Name</label><input id="agentName">
<label>Role</label><input id="agentRole">
</section>
<section>
<div class="tabs">
<button class="active" data-tab="prompt" onclick="tab('prompt')">CALL_PROMPT.md</button>
<button data-tab="soul" onclick="tab('soul')">SOUL.md</button>
<button data-tab="contacts" onclick="tab('contacts')">Trusted Contacts</button>
<button data-tab="tasks" onclick="tab('tasks')">Scheduled Tasks</button>
<button data-tab="memory" onclick="tab('memory')">Memory</button>
<button data-tab="skills" onclick="tab('skills')">Skills</button>
</div>
<div id="prompt"><label>Unknown-caller prompt</label><textarea id="callPrompt"></textarea></div>
<div id="soul" hidden><label>Personality prompt</label><textarea id="soulText"></textarea></div>
<div id="contacts" hidden><label>trustedContacts JSON</label><textarea id="trustedContacts"></textarea></div>
<div id="tasks" hidden><label>scheduled_tasks.json</label><textarea id="scheduledTasks"></textarea></div>
<div id="memory" hidden><div class="warn">Advanced: MEMORY.md, INDEX.md, and summary files are derived agent memory. Bad edits can change recall behavior.</div><label>Memory files JSON map</label><textarea id="memoryFiles"></textarea></div>
<div id="skills" hidden><label>Skill markdown files JSON map</label><textarea id="skillsFiles"></textarea></div>
<pre id="diagnostics" class="diag"></pre>
</section>
</main>
<script>
const geminiVoices={Zephyr:'Bright',Kore:'Firm',Orus:'Firm',Autonoe:'Bright',Umbriel:'Easy-going',Erinome:'Clear',Laomedeia:'Upbeat',Schedar:'Even',Achird:'Friendly',Sadachbia:'Lively',Puck:'Upbeat',Fenrir:'Excitable',Aoede:'Breezy',Enceladus:'Breathy',Algieba:'Smooth',Algenib:'Gravelly',Achernar:'Soft',Gacrux:'Mature',Zubenelgenubi:'Casual',Sadaltager:'Knowledgeable',Charon:'Informative',Leda:'Youthful',Callirrhoe:'Easy-going',Iapetus:'Clear',Despina:'Smooth',Rasalgethi:'Informative',Alnilam:'Firm',Pulcherrima:'Forward',Vindemiatrix:'Gentle',Sulafat:'Warm'};
const xaiVoices={eve:'Energetic',ara:'Warm',rex:'Clear',sal:'Balanced',leo:'Authoritative'};
let state=null;
function voices(){return provider.value==='xai'?xaiVoices:geminiVoices}
function legacyModel(){const m=model.value.trim(); return !m || (provider.value==='xai' && m.startsWith('gemini-')) || (provider.value==='gemini' && (m.startsWith('grok-voice-') || m==='gemini-3.1-flash-live-preview'))}
function modelChanged(){const m=model.value.trim(); const gem=provider.value==='gemini'; thinkingRow.style.display=gem?'':'none'; thinkingHelp.style.display=(gem && m.startsWith('gemini-3.8-live') && !m.includes('extended-thinking'))?'':'none'}
function providerChanged(){const prev=voice.value; voice.innerHTML=''; Object.entries(voices()).forEach(([k,v])=>voice.add(new Option(k+' - '+v,k))); if([...voice.options].some(o=>o.value===prev)) voice.value=prev; else voice.value=provider.value==='xai'?'eve':'Kore'; if(legacyModel()) model.value=provider.value==='xai'?'grok-voice-think-fast-1.0':'gemini-3.8-live'; modelChanged()}
const reasoningDefaults={gemini:'gemini-3.1-pro-preview',xai:'grok-4.6',anthropic:'claude-opus-5'};
const reasoningFamilies={gemini:'gemini-',xai:'grok-',anthropic:'claude-'};
function reasoningProviderChanged(){const m=reasoningModel.value.trim(); const p=reasoningProvider.value; if(!m || !m.startsWith(reasoningFamilies[p])) reasoningModel.value=reasoningDefaults[p]}
function tab(id){document.querySelectorAll('.tabs button').forEach(b=>b.classList.toggle('active',b.dataset.tab===id)); ['prompt','soul','contacts','tasks','memory','skills'].forEach(x=>document.getElementById(x).hidden=x!==id)}
async function load(){const r=await fetch('/api/state'); state=await r.json(); const c=state.config||{}, p=c.providers||{}, a=c.agents||{}, va=a.voice||{}; provider.value=va.provider||c.voiceProvider||'gemini'; providerChanged(); geminiKey.value=(p.gemini&&p.gemini.apiKey)||c.apiKey||''; xaiKey.value=(p.xai&&p.xai.apiKey)||''; anthropicKey.value=(p.anthropic&&p.anthropic.apiKey)||''; const ca=a.chat||{}, fa=a.fable||{}; reasoningProvider.value=ca.provider||'gemini'; reasoningModel.value=ca.model||reasoningDefaults[reasoningProvider.value]; fableModel.value=fa.model||'claude-fable-5-1'; fableEffort.value=fa.effort||'medium'; model.value=va.model||c.model||model.value; if(legacyModel()) model.value=provider.value==='xai'?'grok-voice-think-fast-1.0':'gemini-3.8-live'; thinking.value=va.thinking===true?'true':'false'; thinkingLevel.value=va.thinkingLevel||'low'; modelChanged(); voice.value=va.voice||c.voice||voice.value; ownerName.value=c.ownerName||''; agentName.value=c.agentName||''; agentRole.value=c.agentRole||''; callPrompt.value=state.callPrompt||''; soulText.value=state.soul||''; trustedContacts.value=JSON.stringify(state.trustedContacts||[],null,2); scheduledTasks.value=state.scheduledTasks||'[]'; memoryFiles.value=JSON.stringify(state.memoryFiles||{},null,2); skillsFiles.value=JSON.stringify(state.skills||{},null,2); diagnostics.textContent=(state.diagnostics||[]).join('\n'); status.textContent='Connected to '+state.deviceSerial}
function parseJSON(id,label){try{return JSON.parse(document.getElementById(id).value||'null')}catch(e){throw new Error(label+': '+e.message)}}
async function save(){try{status.textContent='Saving'; const config=state.config||{}; config.providers={gemini:{apiKey:geminiKey.value.trim()},xai:{apiKey:xaiKey.value.trim()},anthropic:{apiKey:anthropicKey.value.trim()}}; config.agents=config.agents||{}; config.agents.voice={provider:provider.value,model:model.value.trim(),voice:voice.value,thinking:thinking.value==='true',thinkingLevel:thinkingLevel.value}; ['chat','expert','scheduler','memory'].forEach(r=>{config.agents[r]={provider:reasoningProvider.value,model:reasoningModel.value.trim()}}); config.agents.fable={provider:'anthropic',model:fableModel.value.trim(),effort:fableEffort.value}; config.ownerName=ownerName.value.trim(); config.agentName=agentName.value.trim(); config.agentRole=agentRole.value.trim(); const payload={config,callPrompt:callPrompt.value,soul:soulText.value,trustedContacts:parseJSON('trustedContacts','Trusted contacts'),scheduledTasks:scheduledTasks.value,memoryFiles:parseJSON('memoryFiles','Memory files'),skills:parseJSON('skillsFiles','Skills')}; const r=await fetch('/api/save',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(payload)}); if(!r.ok) throw new Error(await r.text()); const out=await r.json(); diagnostics.textContent=(out.diagnostics||[]).join('\n'); status.textContent='Saved and app restarted'}catch(e){status.textContent='Save failed'; alert(e.message)}}
load();
</script>
</body>
</html>`
