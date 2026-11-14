import { useEffect, useState, useSyncExternalStore } from "react";

import { formatBytes } from "../api/format";
import type {
  DesktopHost,
  DesktopUpdateInfo,
  DesktopUpdateInstallResult,
  DesktopUpdatesState,
} from "../app/desktop";
import { showError } from "../app/errorStore";
import { useI18n } from "../app/i18n";
import { GitHubMarkdown } from "../components/GitHubMarkdown";
import { Button, Card, DataLine, Dialog, Spinner, Switch } from "../components/ui";
import styles from "./UpdateViews.module.css";

let updateDialogVisible = false;
const updateDialogListeners = new Set<() => void>();

function notifyUpdateDialogListeners() {
  for (const listener of updateDialogListeners) {
    listener();
  }
}

export function openUpdateDialog() {
  if (!updateDialogVisible) {
    updateDialogVisible = true;
    notifyUpdateDialogListeners();
  }
}

function closeUpdateDialog() {
  if (updateDialogVisible) {
    updateDialogVisible = false;
    notifyUpdateDialogListeners();
  }
}

function subscribeUpdateDialog(listener: () => void): () => void {
  updateDialogListeners.add(listener);
  return () => {
    updateDialogListeners.delete(listener);
  };
}

export function useUpdatesState(host: DesktopHost): DesktopUpdatesState | null {
  const [state, setState] = useState<DesktopUpdatesState | null>(null);

  useEffect(() => {
    let stale = false;
    let pushed = false;
    const unsubscribe = host.updates.onStateChanged((value) => {
      pushed = true;
      setState(() => value);
    });
    host.updates
      .state()
      .then((value) => {
        if (!stale && !pushed) {
          setState(() => value);
        }
      })
      .catch(showError);
    return () => {
      stale = true;
      unsubscribe();
    };
  }, [host]);

  return state;
}

export function UpdatesGate(props: { host: DesktopHost }) {
  const host = props.host;
  const state = useUpdatesState(host);
  const dialogVisible = useSyncExternalStore(subscribeUpdateDialog, () => updateDialogVisible);

  useEffect(() => host.updates.onPresentRequested(openUpdateDialog), [host]);
  useEffect(() => {
    if (state?.promptUpdate || (state?.stableOnly && (state.downloading || state.installing))) openUpdateDialog();
  }, [state?.promptUpdate, state?.stableOnly, state?.downloading, state?.installing]);

  const currentVersion = state?.info?.versionName;
  useEffect(() => {
    if (dialogVisible && currentVersion !== undefined) {
      void host.updates.markShown().catch(() => {});
    }
  }, [dialogVisible, currentVersion, host]);

  if (state === null || !state.supported) {
    return null;
  }

  return (
    <>
      {!state.prompted && <UpdateCheckPromptDialog host={host} />}
      {dialogVisible && state.info !== null && (state.stableOnly
        ? <WboxUpdateDialog host={host} state={state} info={state.info} />
        : <UpdateDialog host={host} info={state.info} state={state} />)}
      {!dialogVisible && state.stableOnly && (state.downloading || state.installing || (state.error && state.info)) &&
        <button className={styles.minimizedProgress} onClick={openUpdateDialog} type="button">
          查看下载进度 <span>{state.error ? '需要重试' : state.installing ? '准备安装' : `${Math.round(state.downloadProgress * 100)}%`}</span>
        </button>}
    </>
  );
}

export function WboxUpdateSettings({ host }: { host: DesktopHost }) {
  const state = useUpdatesState(host);
  const [message, setMessage] = useState('');
  if (!state) return null;
  if (!state.supported) return state.error ? <Card title="应用更新" icon="sync"><p className={styles.updateError}>{state.error}</p></Card> : null;
  const check = () => {
    setMessage('');
    void host.updates.check().then(info => { if (info) openUpdateDialog(); else setMessage('当前已是最新稳定版。'); }).catch(showError);
  };
  return <Card title="应用更新" icon="sync">
    <DataLine label="当前版本" value={state.currentVersion ?? '…'} />
    <div className="wbox-row"><span className="wbox-grow">自动检查更新<small>仅检查 GitHub 稳定版，确认后才下载</small></span>
      <Switch label="自动检查更新" value={state.checkUpdateEnabled} onChange={value => {
        void host.updates.setCheckUpdateEnabled(value).then(() => { if (value) check(); }).catch(showError);
      }} /></div>
    <div className="row-actions">
      <Button onClick={check} disabled={state.checking || state.downloading || state.installing}>{state.checking && <Spinner />}检查更新</Button>
      {state.info && <Button variant="primary" onClick={openUpdateDialog}>{state.downloading || state.installing ? '查看下载进度' : '查看更新'}</Button>}
    </div>
    {(message || state.notice) && <p className="wbox-hint" role="status">{message || state.notice}</p>}
    {state.error && <p className={styles.updateError} role="alert">{state.error}</p>}
  </Card>;
}

function WboxUpdateDialog({ host, state, info }: { host: DesktopHost; state: DesktopUpdatesState; info: DesktopUpdateInfo }) {
  const busy = state.downloading || state.installing;
  const progress = Math.max(0, Math.min(100, Math.round(state.downloadProgress * 100)));
  const label = state.installing ? '正在准备安装…' : state.phase === 'verifying' ? '正在校验安装包…' : '正在下载…';
  return <Dialog onClose={closeUpdateDialog} className={styles.updateDialog}>
    <h3>{busy ? '正在更新 Wbox' : '发现新版本'}</h3>
    <p className="dialog-message">{state.currentVersion} → {info.versionName} · {formatBytes(info.fileSize)}</p>
    {info.releaseNotes && <div className={styles.releaseNotes}><GitHubMarkdown text={info.releaseNotes} /></div>}
    {busy && <div className={styles.progressRow}>
      <div className={styles.progressDetails}><span>{label}</span><span>{progress}%</span></div>
      <div className={styles.progressTrack} role="progressbar" aria-label="更新下载进度" aria-valuemin={0} aria-valuemax={100} aria-valuenow={progress}>
        <div className={styles.progressFill} style={{ width: `${progress}%` }} />
      </div>
      <div className={styles.progressDetails}><span>{formatBytes(state.downloadBytes ?? 0)} / {formatBytes(info.fileSize)}</span><span>{formatBytes(state.downloadSpeed ?? 0)}/s</span></div>
      <p className="wbox-hint">收起或关闭主窗口后，下载仍会继续。</p>
    </div>}
    {!busy && <p className="wbox-hint">确认后开始下载。下载完成将关闭 Wbox 并打开安装器，结束后恢复原来的连接状态，并清理本次安装包。</p>}
    {state.error && <p className={styles.updateError} role="alert">{state.error}</p>}
    <div className="row-actions dialog-actions">
      <Button href={info.releaseURL} target="_blank" rel="noreferrer">查看发布说明</Button>
      <span className={styles.actionsSpacer} />
      <Button onClick={closeUpdateDialog}>{busy ? '收起' : '暂不更新'}</Button>
      {!busy && <Button variant="primary" onClick={() => { void host.updates.downloadAndInstall().catch(showError); }}>{state.error ? '重试更新' : '下载并安装'}</Button>}
    </div>
  </Dialog>;
}

function UpdateCheckPromptDialog(props: { host: DesktopHost }) {
  const host = props.host;
  const { t } = useI18n();
  const [busy, setBusy] = useState(false);

  const decline = () => {
    void host.updates.setPrompted().catch(showError);
  };

  const accept = () => {
    setBusy(true);
    host.updates
      .setCheckUpdateEnabled(true)
      .then(() => host.updates.check())
      .then((info) => {
        if (info !== null) {
          openUpdateDialog();
        }
      })
      .catch(showError)
      .finally(() => setBusy(false));
  };

  return (
    <Dialog onClose={decline}>
      <h3>{t("Check Update")}</h3>
      <p className="dialog-message">
        {t("Would you like to enable automatic update checking from GitHub?")}
      </p>
      <div className="row-actions dialog-actions">
        <Button disabled={busy} onClick={decline}>
          {t("No, thanks")}
        </Button>
        <Button variant="primary" disabled={busy} onClick={accept}>
          {t("Ok")}
        </Button>
      </div>
    </Dialog>
  );
}

function UpdateDialog(props: {
  host: DesktopHost;
  info: DesktopUpdateInfo;
  state: DesktopUpdatesState;
}) {
  const host = props.host;
  const { t } = useI18n();
  const [elevationReason, setElevationReason] = useState<
    Exclude<DesktopUpdateInstallResult, "started"> | null
  >(null);
  const [elevating, setElevating] = useState(false);
  const info = props.info;
  const busy = props.state.downloading || props.state.installing || elevating;
  const progressIndeterminate = props.state.installing || props.state.downloadProgress <= 0;
  const downloadProgress = Math.round(props.state.downloadProgress * 100);

  const close = () => {
    if (!busy) {
      closeUpdateDialog();
    }
  };

  const install = () => {
    void host.updates
      .downloadAndInstall()
      .then((result) => {
        if (result !== "started") {
          setElevationReason(result);
        }
      })
      .catch(showError);
  };

  const installWithElevation = () => {
    setElevating(true);
    void host.updates
      .installWithElevation()
      .then((launched) => {
        if (!launched) {
          setElevationReason(null);
        }
      })
      .catch(showError)
      .finally(() => setElevating(false));
  };

  if (elevationReason !== null) {
    const signerMismatch = elevationReason === "signer-mismatch";
    return (
      <Dialog
        onClose={() => {
          if (!elevating) {
            setElevationReason(null);
          }
        }}
      >
        <h3>{t(signerMismatch ? "Signature mismatch" : "Not a newer version")}</h3>
        <p className="dialog-message">
          {t(
            signerMismatch
              ? "The new version is signed differently from the current application. Do you want to continue installing?"
              : "This installer is not newer than the current application. Do you want to continue installing?",
          )}
        </p>
        <div className="row-actions dialog-actions">
          <Button disabled={elevating} onClick={() => setElevationReason(null)}>
            {t("Cancel")}
          </Button>
          <Button variant="danger" disabled={elevating} onClick={installWithElevation}>
            {t("Continue")}
          </Button>
        </div>
      </Dialog>
    );
  }

  return (
    <Dialog onClose={close} className={styles.updateDialog}>
      <h3>{t("Check Update")}</h3>
      <p className="dialog-message">
        {t("New version available: {version}", { version: info.versionName })}
        {info.fileSize > 0 && ` (${formatBytes(info.fileSize)})`}
      </p>
      {info.releaseNotes !== "" && (
        <div className={styles.releaseNotes}>
          <GitHubMarkdown text={info.releaseNotes} />
        </div>
      )}
      {busy && (
        <div className={styles.progressRow}>
          <div
            className={styles.progressTrack}
            role="progressbar"
            aria-label={t(props.state.installing ? "Installing..." : "Downloading...")}
            aria-valuemin={0}
            aria-valuemax={100}
            aria-valuenow={progressIndeterminate ? undefined : downloadProgress}
          >
            <div
              className={progressIndeterminate ? styles.progressIndeterminate : styles.progressFill}
              style={progressIndeterminate ? undefined : { width: `${downloadProgress}%` }}
            />
          </div>
        </div>
      )}
      <div className="row-actions dialog-actions">
        <Button href={info.releaseURL} target="_blank" rel="noreferrer">
          {t("View Release")}
        </Button>
        <span className={styles.actionsSpacer} />
        <Button disabled={busy} onClick={close}>
          {t("Cancel")}
        </Button>
        <Button variant="primary" disabled={busy} onClick={install}>
          {t("Update")}
        </Button>
      </div>
    </Dialog>
  );
}
