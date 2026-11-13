import assert from 'node:assert/strict';
import {trayPlacement} from '../../desktop/wbox/tray-position.ts';

const desktop={x:0,y:0,width:1920,height:1040};
const anchor={x:1740,y:1052,width:24,height:24};
const left=trayPlacement(anchor,desktop);
assert.equal(left.side,'left');
assert.equal(left.align,'bottom','Bottom taskbar keeps the root panel near the icon while opening left');
assert.ok(left.x+left.width-40 <= anchor.x-8,'Root panel stays to the left of the icon');
assert.equal(trayPlacement({x:1000,y:400,width:24,height:24},desktop).side,'left','Left is preferred, not whichever side has more space');
assert.equal(trayPlacement({x:0,y:400,width:24,height:24},desktop).side,'right','Left-edge fallback remains accessible');
for(const area of [desktop,{x:-1920,y:-200,width:1920,height:1080},{x:2000,y:0,width:1280,height:720},{x:0,y:0,width:800,height:400}]) {
  for(const icon of [{x:area.x,y:area.y},{x:area.x+area.width-24,y:area.y+area.height},{x:area.x+area.width/2,y:area.y+area.height/2}]) {
    const p=trayPlacement({...icon,width:24,height:24},area);
    assert.ok(p.x>=area.x&&p.y>=area.y);
    assert.ok(p.x+p.width<=area.x+area.width&&p.y+p.height<=area.y+area.height,'Menu fits the matching monitor work area');
  }
}
console.log('PASS tray placement: left preference, edge fallback, negative monitor coordinates and small work areas');
