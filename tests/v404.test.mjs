import fs from 'node:fs';
import assert from 'node:assert/strict';

const html=fs.readFileSync(new URL('../index.html',import.meta.url),'utf8');
const kotlin=fs.readFileSync(
  new URL('../android-app/app/src/main/java/com/mbmlife/companion/MainActivity.kt',import.meta.url),
  'utf8'
);

assert.match(html,/const APP_VERSION='v404'/);
assert.match(kotlin,/@JavascriptInterface\s+fun shareText\(title: String\?, text: String\?, url: String\?\)/);
assert.match(kotlin,/Intent\(Intent\.ACTION_SEND\)/);
assert.match(kotlin,/putExtra\(Intent\.EXTRA_TEXT, payload\)/);
assert.match(kotlin,/Intent\.createChooser\(sendIntent/);

const handlerStart=html.indexOf("case 'inv-share':{");
const handlerEnd=html.indexOf("case 'inv-revoke':",handlerStart);
assert.ok(handlerStart>=0&&handlerEnd>handlerStart,'invite Share handler must exist');
const handler=html.slice(handlerStart,handlerEnd);
assert.match(handler,/window\.MbmNativeAuth\.shareText\('MBM Family',msg,link\)/);
assert.match(handler,/navigator\.share\(\{title:'MBM Family',text:msg,url:link\}\)\.catch\(\(\)=>famCopy\(link\)\)/);
assert.ok(
  handler.indexOf("window.MbmNativeAuth.shareText('MBM Family',msg,link)")<
    handler.indexOf("navigator.share({title:'MBM Family'"),
  'Android native chooser must be preferred over unreliable WebView Web Share'
);
assert.doesNotMatch(handler,/navigator\.share[\s\S]*catch\(\(\)=>\{\}\)/);

console.log('v404 native invitation sharing regression tests passed');
