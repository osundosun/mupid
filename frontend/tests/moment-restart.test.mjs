import test from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import ts from 'typescript';
const source = (await readFile(new URL('../hooks/use-mupid.ts', import.meta.url),'utf8')).replaceAll('\r\n','\n');
const load = async code => import('data:text/javascript;base64,' + Buffer.from(ts.transpileModule(code,{compilerOptions:{target:ts.ScriptTarget.ES2022,module:ts.ModuleKind.ESNext}}).outputText).toString('base64'));
const segment = source.slice(source.indexOf('  const playSavedMoment ='), source.indexOf('  const stopSavedMomentPlayback ='));
const {make} = await load(`export function make(d:any) {
 type SavedMoment = any;
 const {activeRef, momentSequence, getPlayer, startPlayback} = d;
 const previewEnd = {current:null};
 const setSavedMomentPlayback = () => {};
 const placePlayedTrack = () => ({uid:'q1'});
 const momentPlaybackRanges = (moments:any[]) => moments;
 ${segment}
 return playSavedMoment;
}`);
test('구간 재생 버튼은 A → B → A 및 같은 구간 재클릭에도 시작점을 재생한다', () => {
 const track = {id:1};
 const calls = [];
 const play = make({activeRef:{current:track}, momentSequence:{current:null},
 getPlayer:()=>({store:{get:()=>({status:'playing'})},pause:()=>{}}),
 startPlayback:(_track,start)=>calls.push(start)});
 const a = {track,start:91,end:111}, b = {track,start:111,end:131};
 for (const moment of [a,b,a,a,b]) play(moment);
 assert.deepEqual(calls,[91,111,91,91,111]);
});
const start = source.indexOf('        const pending = playbackSeek.current');
const end = source.indexOf('\n      }', start);
const {run} = await load(`export async function run(d:any) {
 const {playbackSeek,requestId,id,player,start} = d;
 ${source.slice(start,end)}
}`);
test('이전 seek가 늦게 완료돼도 마지막 클릭 구간만 재생한다', async () => {
 let release;
 const firstDone = new Promise(resolve => {release=resolve});
 const requestId = {current:1}, playbackSeek = {current:Promise.resolve()};
 const calls=[];
 const player={seek:async pos=>{calls.push(['seek',pos]); if(pos===91) await firstDone;},play:async()=>calls.push(['play'])};
 const first=run({requestId,playbackSeek,id:1,player,start:91});
 await new Promise(resolve=>setImmediate(resolve));
 requestId.current=2;
 const second=run({requestId,playbackSeek,id:2,player,start:111});
 release();
 await Promise.all([first,second]);
 assert.deepEqual(calls,[['seek',91],['seek',111],['play']]);
});
