import { useEffect, useId, useState } from 'react';
import { AdminApi } from '../api/endpoints';
import { errorMessage } from '../api/client';
import type { AiSettingsDto, UpdateAiSettingsRequest } from '../types';
import { formatTimeAgo } from '../lib/format';
import { Button } from './ui/Button';
import { ConfirmDialog } from './ui/Dialog';
import { Alert } from './ui/Feedback';
import { useToast } from './ui/Toast';
import { ExternalLinkIcon, LockIcon, SparklesIcon } from './ui/Icons';

/**
 * Admin-only: the AI provider, model and key every user of this InboxIQ
 * runs on. Changes apply to everyone immediately; the key is encrypted on
 * the server and never sent back (only its last four characters).
 */
export function AiSettingsSection() {
  const toast = useToast();
  const ids = useId();
  const [settings, setSettings] = useState<AiSettingsDto | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [provider, setProvider] = useState('');
  const [model, setModel] = useState('');
  const [apiKey, setApiKey] = useState('');
  const [baseUrl, setBaseUrl] = useState('');
  const [saving, setSaving] = useState(false);
  const [testing, setTesting] = useState(false);
  const [resetting, setResetting] = useState(false);
  const [confirmReset, setConfirmReset] = useState(false);
  const [result, setResult] = useState<{ ok: boolean; text: string } | null>(null);

  const applyView = (view: AiSettingsDto) => {
    setSettings(view);
    setProvider(view.provider);
    setModel(view.model ?? '');
    setBaseUrl(view.baseUrl ?? '');
    setApiKey('');
  };

  useEffect(() => {
    AdminApi.aiSettings()
      .then(applyView)
      .catch((err) => setLoadError(errorMessage(err, 'Could not load the AI settings.')));
  }, []);

  const option = settings?.providers.find((p) => p.id === provider);
  const needsBaseUrl = !!option?.requiresBaseUrl;
  const dirty =
    !!settings &&
    (provider !== settings.provider ||
      model.trim() !== (settings.model ?? '') ||
      apiKey.trim() !== '' ||
      (needsBaseUrl && baseUrl.trim() !== (settings.baseUrl ?? '')));

  const request = (): UpdateAiSettingsRequest => ({
    provider,
    model: model.trim(),
    apiKey: apiKey.trim() || undefined,
    baseUrl: needsBaseUrl ? baseUrl.trim() : undefined,
  });

  const changeProvider = (id: string) => {
    setProvider(id);
    setApiKey('');
    setResult(null);
    if (!settings) return;
    const next = settings.providers.find((p) => p.id === id);
    if (id === settings.provider) setModel(settings.model ?? '');
    else setModel(next?.suggestedModels[0] ?? '');
  };

  const keyPlaceholder = (): string => {
    if (!settings) return '';
    if (provider === settings.provider && settings.hasApiKey) {
      return settings.usingEnvironmentKey
        ? `Using the environment's key ${settings.apiKeyHint ?? ''} — leave blank to keep it`
        : `Saved key ${settings.apiKeyHint ?? ''} — leave blank to keep it`;
    }
    if (provider === settings.environmentDefault.provider && settings.environmentDefault.hasApiKey) {
      return "Leave blank to use the environment's key";
    }
    if (needsBaseUrl) return 'Only if your endpoint needs one';
    return `Paste your ${option?.label ?? ''} API key`;
  };

  const test = async () => {
    setTesting(true);
    setResult(null);
    try {
      const outcome = await AdminApi.testAiSettings(request());
      setResult({ ok: outcome.ok, text: outcome.message });
    } catch (err) {
      setResult({ ok: false, text: errorMessage(err, 'The test could not be run.') });
    } finally {
      setTesting(false);
    }
  };

  const save = async () => {
    setSaving(true);
    setResult(null);
    try {
      const view = await AdminApi.saveAiSettings(request());
      applyView(view);
      toast.success('AI provider updated', `Everyone now uses ${view.providerLabel} · ${view.model}.`);
    } catch (err) {
      setResult({ ok: false, text: errorMessage(err, 'Could not save the AI settings.') });
    } finally {
      setSaving(false);
    }
  };

  const reset = async () => {
    setResetting(true);
    try {
      const view = await AdminApi.resetAiSettings();
      applyView(view);
      setConfirmReset(false);
      setResult(null);
      toast.success('AI settings reset', `Back to the environment defaults: ${view.providerLabel} · ${view.model}.`);
    } catch (err) {
      toast.error('Could not reset the AI settings', errorMessage(err, 'Please try again.'));
    } finally {
      setResetting(false);
    }
  };

  return (
    <section className="rounded-2xl border border-white/[0.06] bg-ink-800 p-5 md:p-6">
      <div className="flex items-center gap-2">
        <h2 className="text-sm font-semibold text-white/90">AI provider</h2>
        <span className="rounded-full bg-accent-500/10 px-2 py-0.5 text-2xs font-semibold text-accent-300">Admin</span>
      </div>
      <p className="mt-1 text-sm leading-relaxed text-white/45">
        The AI service that writes summaries and drafts for everyone using this InboxIQ. Changes apply immediately.
      </p>

      {loadError && (
        <Alert tone="danger" className="mt-5">
          {loadError}
        </Alert>
      )}

      {!settings && !loadError && (
        <div className="mt-5 space-y-3" aria-hidden="true">
          <span className="skeleton h-12 w-full rounded-xl" />
          <span className="skeleton h-9 w-full" />
          <span className="skeleton h-9 w-full" />
        </div>
      )}

      {settings && (
        <>
          {/* What's live right now */}
          <div className="mt-5 flex flex-wrap items-center gap-x-4 gap-y-2 rounded-xl border border-white/[0.06] bg-ink-900/50 px-4 py-3">
            <span className="flex items-center gap-2 text-sm font-medium text-white/85">
              <SparklesIcon className="h-4 w-4 text-accent-400" />
              {settings.providerLabel}
            </span>
            <span className="font-mono text-xs text-white/60">{settings.model || 'no model set'}</span>
            <span className="flex items-center gap-1.5 text-xs text-white/40">
              <LockIcon className="h-3 w-3" />
              {settings.hasApiKey ? `key ${settings.apiKeyHint ?? 'set'}` : 'no key'}
            </span>
            <span className="ml-auto rounded-full bg-white/[0.05] px-2.5 py-1 text-2xs font-medium text-white/50">
              {settings.source === 'APP'
                ? `Set in app${settings.updatedAt ? ` ${formatTimeAgo(new Date(settings.updatedAt))}` : ''}`
                : 'From environment variables'}
            </span>
          </div>

          {!settings.hasApiKey && settings.provider !== 'CUSTOM' && (
            <Alert tone="warning" className="mt-3">
              AI features are off until an API key is set.
            </Alert>
          )}

          <div className="mt-5 grid gap-4 sm:grid-cols-2">
            <label className="block">
              <span className="field-label">Provider</span>
              <select value={provider} onChange={(event) => changeProvider(event.target.value)} className="field">
                {settings.providers.map((p) => (
                  <option key={p.id} value={p.id}>
                    {p.label}
                  </option>
                ))}
              </select>
            </label>

            <label className="block">
              <span className="field-label">Model</span>
              <input
                value={model}
                onChange={(event) => setModel(event.target.value)}
                list={`${ids}-models`}
                placeholder="Any model your provider offers"
                spellCheck={false}
                autoComplete="off"
                className="field font-mono text-[13px]"
              />
              <datalist id={`${ids}-models`}>
                {option?.suggestedModels.map((m) => (
                  <option key={m} value={m} />
                ))}
              </datalist>
            </label>

            {needsBaseUrl && (
              <label className="block sm:col-span-2">
                <span className="field-label">Base URL (OpenAI-compatible)</span>
                <input
                  value={baseUrl}
                  onChange={(event) => setBaseUrl(event.target.value)}
                  placeholder="https://your-gateway.example.com/v1"
                  spellCheck={false}
                  autoComplete="off"
                  className="field font-mono text-[13px]"
                />
              </label>
            )}

            <label className="block sm:col-span-2">
              <span className="field-label flex items-center justify-between">
                API key
                {option?.keyUrl && (
                  <a
                    href={option.keyUrl}
                    target="_blank"
                    rel="noopener noreferrer"
                    className="inline-flex items-center gap-1 font-medium text-accent-400 hover:text-accent-300"
                  >
                    Get an API key
                    <ExternalLinkIcon className="h-3 w-3" />
                  </a>
                )}
              </span>
              <input
                type="password"
                value={apiKey}
                onChange={(event) => setApiKey(event.target.value)}
                placeholder={keyPlaceholder()}
                autoComplete="new-password"
                spellCheck={false}
                className="field font-mono text-[13px]"
              />
            </label>
          </div>

          {result && (
            <Alert tone={result.ok ? 'success' : 'danger'} className="mt-4">
              {result.text}
            </Alert>
          )}

          <div className="mt-5 flex flex-col-reverse gap-2 sm:flex-row sm:items-center">
            {settings.source === 'APP' && (
              <Button variant="ghost" onClick={() => setConfirmReset(true)} disabled={saving || testing} className="sm:mr-auto">
                Reset to defaults
              </Button>
            )}
            <div className="flex flex-col-reverse gap-2 sm:ml-auto sm:flex-row">
              <Button
                variant="secondary"
                onClick={test}
                loading={testing}
                disabled={saving || !model.trim()}
                icon={<SparklesIcon className="h-4 w-4" />}
              >
                {testing ? 'Testing…' : 'Test connection'}
              </Button>
              <Button variant="primary" onClick={save} loading={saving} disabled={testing || !dirty || !model.trim()}>
                Save changes
              </Button>
            </div>
          </div>
          <p className="mt-3 text-xs text-white/30">
            Keys are encrypted on the server and never shown again — only their last four characters.
          </p>
        </>
      )}

      <ConfirmDialog
        open={confirmReset}
        onClose={() => !resetting && setConfirmReset(false)}
        onConfirm={reset}
        loading={resetting}
        tone="primary"
        title="Reset the AI settings?"
        description={
          settings
            ? `Everyone goes back to the environment defaults: ${settings.environmentDefault.providerLabel} · ${settings.environmentDefault.model ?? 'no model'}. The key saved here is deleted.`
            : undefined
        }
        confirmLabel="Reset"
      />
    </section>
  );
}
