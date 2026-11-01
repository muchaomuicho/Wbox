import { useState } from 'react';
import { Button, Dialog, Field, Select, Switch } from './components/ui';
import { call } from '../../wbox/transport';

export function NodeEditor({ index, initial, onClose }: { index: number; initial: {node: Record<string, any>; revision: string}; onClose: () => void }) {
  const [node, setNode] = useState(initial.node);
  const [options, setOptions] = useState(JSON.stringify(initial.node.options || {}, null, 2));
  const nativeKey = initial.node.nativeSingbox ? 'nativeSingbox' : initial.node.nativeMihomo ? 'nativeMihomo' : null;
  const [native, setNative] = useState(nativeKey ? JSON.stringify(initial.node[nativeKey], null, 2) : '');
  const [busy, setBusy] = useState(false), [error, setError] = useState('');
  const change = (key: string, value: unknown) => {setError('');setNode({...node, [key]: value});};
  const field = (key: string, label: string, type = 'text') => <Field label={label}><input className="input" type={type} autoComplete="off" value={node[key] ?? ''} onChange={e => change(key, type === 'number' ? Number(e.target.value) : e.target.value)} /></Field>;
  const save = async () => {
    setBusy(true); setError('');
    try {
      const edited = nativeKey ? {...node, [nativeKey]: JSON.parse(native)} : {...node, options: JSON.parse(options)};
      await call('writeNode', {index, revision: initial.revision, node: edited}); onClose();
    } catch (e) { setError(e instanceof Error ? e.message : String(e)); } finally { setBusy(false); }
  };
  return <Dialog className="wbox-node-dialog" onClose={() => {if(!busy) onClose();}}>
    <h3>编辑节点</h3>
    <div className="wbox-dialog-body">
      {nativeKey ? <Field label={nativeKey === 'nativeSingbox' ? 'sing-box 原生节点（JSON）' : 'mihomo 原生节点（JSON）'}><textarea className="input wbox-editor" value={native} onChange={e => setNative(e.target.value)} spellCheck={false} /></Field> : <>
        {field('name', '名称')}
        <div className="wbox-form-grid">{field('server', '服务器地址')}{field('port', '端口', 'number')}</div>
        <Field label="协议"><input className="input" value={node.protocol.toUpperCase()} readOnly /></Field>
        {['vmess','vless','tuic'].includes(node.protocol) && field('uuid', 'UUID')}
        {['http','socks','naive'].includes(node.protocol) && field('username', '用户名')}
        {!['vmess','vless'].includes(node.protocol) && field('password', node.protocol === 'wireguard' ? '私钥' : '密码', 'password')}
        {['ss','vmess'].includes(node.protocol) && field('cipher', '加密方式')}
        {node.protocol === 'vmess' && field('alterId', 'Alter ID', 'number')}
        {['vmess','vless','trojan'].includes(node.protocol) && <>
          <Field label="传输协议"><Select value={node.network} options={['tcp','ws','grpc','http','h2'].map(value => ({value,label:value}))} onChange={v => change('network',v)} /></Field>
          {node.network !== 'tcp' && <div className="wbox-form-grid">{field('host','Host')}{field('path',node.network === 'grpc' ? 'Service name' : '路径')}</div>}
        </>}
        {node.protocol === 'vless' && field('flow','Flow')}
        {!['ss','wireguard'].includes(node.protocol) && <div className="toggle-line"><span>TLS</span><Switch label="TLS" value={node.tls} onChange={v => change('tls',v)} /></div>}
        {node.tls && <>{field('sni','SNI')}<div className="toggle-line"><span>跳过证书验证</span><Switch label="跳过证书验证" value={node.insecure} onChange={v => change('insecure',v)} /></div></>}
        <details className="wbox-advanced"><summary>高级参数</summary>
          {['vmess','vless','trojan'].includes(node.protocol) && <>{field('fingerprint','TLS 指纹')}{field('realityKey','Reality 公钥')}{field('realityShortId','Reality Short ID')}</>}
          <Field label="其他协议参数（JSON）"><textarea className="input wbox-editor compact" value={options} onChange={e => setOptions(e.target.value)} spellCheck={false} /></Field>
        </details>
      </>}
      {node.sourceSubscription && <p className="wbox-hint">此节点来自订阅，下次更新订阅会覆盖修改。需要长期保留时，可先克隆为手动节点。</p>}
      {error && <p role="alert" className="wbox-error">{error}</p>}
    </div>
    <div className="dialog-actions row-actions"><Button disabled={busy} onClick={onClose}>取消</Button><Button variant="primary" disabled={busy} onClick={() => void save()}>{busy ? '正在保存…' : '保存'}</Button></div>
  </Dialog>;
}
