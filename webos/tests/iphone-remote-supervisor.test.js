'use strict';
const test=require('node:test'),assert=require('node:assert/strict'),fs=require('fs'),os=require('os'),path=require('path'),cp=require('child_process');
const delay=ms=>new Promise(r=>setTimeout(r,ms));
// The supervisor takes its single-instance lock with flock (util-linux): on webOS and Linux, not on macOS.
const noFlock=cp.spawnSync('sh',['-c','command -v flock']).status!==0&&'flock is not installed (util-linux; not on macOS)';
test('production supervisor survives more than five crashes, keeps one instance, and stops its child',{skip:noFlock},async()=>{
 const root=fs.mkdtempSync(path.join(os.tmpdir(),'remote-supervisor-'));fs.mkdirSync(root+'/app');
 // Adapt the executable path and shorten retry waits; exercise the real supervisor loop.
 let script=fs.readFileSync(require.resolve('../iphone-remote/start'),'utf8').replace('/usr/bin/node',"'"+process.execPath.replace(/'/g,"'\\''")+"'");
 script=script.replace('sleep "$retry_delay"','sleep 0.05');
 fs.writeFileSync(root+'/app/start',script);
 fs.writeFileSync(root+'/app/server.js',`const fs=require('fs'),r=process.env.OMARCHY_REMOTE_ROOT;let count=fs.existsSync(r+'/count')?+fs.readFileSync(r+'/count'):0;fs.writeFileSync(r+'/count',String(++count));if(count<7){console.log('x'.repeat(70000));process.exit(1);}setInterval(()=>{},1000);`);
 const env={...process.env,OMARCHY_REMOTE_ROOT:root};const child=cp.spawn('sh',[root+'/app/start'],{env,stdio:'ignore'});const exited=new Promise(r=>child.once('exit',r));
 let servicePid;
 try{
  for(let n=0;n<80;n++){if(fs.existsSync(root+'/count')&&+fs.readFileSync(root+'/count')===7)break;await delay(100);}
  assert.equal(+fs.readFileSync(root+'/count'),7);assert.ok(fs.existsSync(root+'/service.log.old'));
  servicePid=+fs.readFileSync(root+'/service.pid');
  await new Promise((resolve,reject)=>cp.execFile('sh',[root+'/app/start'],{env},e=>e?reject(e):resolve()));
  assert.equal(+fs.readFileSync(root+'/service.pid'),servicePid);
  child.kill('SIGTERM');await exited;
  for(let i=0;i<20;i++){try{process.kill(servicePid,0);}catch(e){break;}await delay(50);}
  assert.throws(()=>process.kill(servicePid,0));
 }finally{if(child.exitCode===null)child.kill('SIGTERM');if(servicePid){try{process.kill(servicePid,'SIGKILL');}catch(e){}}fs.rmSync(root,{recursive:true,force:true});}
});

for (const phase of ['server', 'retry']) {
 for (const signal of ['SIGTERM', 'SIGINT', 'SIGHUP']) {
  test(`supervisor reaps the ${phase} child before exiting on ${signal}`, {skip:noFlock, timeout:5000}, async()=>{
   const root=fs.mkdtempSync(path.join(os.tmpdir(),'remote-supervisor-signal-'));
   fs.mkdirSync(root+'/app');
   const node="'"+process.execPath.replace(/'/g,"'\\''")+"'";
   // Exercise the trap with a child that takes time to stop after receiving TERM.
   const slowChild=`
    process.on('SIGTERM',()=>setTimeout(()=>process.exit(0),150));
    require('fs').writeFileSync(process.env.OMARCHY_REMOTE_ROOT+'/ready',String(process.pid)+'\\n');
    setInterval(()=>{},1000);
   `;
   const script=fs.readFileSync(require.resolve('../iphone-remote/start'),'utf8')
    .replace('/usr/bin/node',node)
    .replace('sleep "$retry_delay"',node+' "$ROOT/app/retry.js" "$retry_delay"');
   fs.writeFileSync(root+'/app/start',script);
   fs.writeFileSync(root+'/app/server.js',phase==='server'?slowChild:'process.exit(1);');
   fs.writeFileSync(root+'/app/retry.js',slowChild);
   const supervisor=cp.spawn('sh',[root+'/app/start'],{
    env:{...process.env,OMARCHY_REMOTE_ROOT:root},stdio:'ignore'
   });
   let stopped=false;
   const exited=new Promise(resolve=>supervisor.once('exit',(code,signal)=>{
    stopped=true;
    resolve({code,signal});
   }));
   let childPid;
   try {
    for (let n=0;n<100;n++) {
     const ready=fs.existsSync(root+'/ready')&&fs.readFileSync(root+'/ready','utf8');
     if (ready&&ready.endsWith('\n')) { childPid=Number(ready); break; }
     await delay(10);
    }
    assert.ok(childPid>0,'child reached the active phase');
    assert.ok(supervisor.kill(signal));
    assert.deepEqual(await exited,{code:0,signal:null});
    assert.throws(()=>process.kill(childPid,0),{code:'ESRCH'});
   } finally {
    if (!stopped) supervisor.kill('SIGTERM');
    if (childPid) {
     try { process.kill(childPid,'SIGKILL'); } catch (e) { if (e.code!=='ESRCH') throw e; }
    }
    await exited;
    fs.rmSync(root,{recursive:true,force:true});
   }
  });
 }
}
