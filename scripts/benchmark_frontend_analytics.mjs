import assert from 'node:assert/strict';
import { writeFileSync } from 'node:fs';
import { performance } from 'node:perf_hooks';
import { aggregateChartRows } from '../frontend/src/features/kpi/utils/chartData.js';

const rows=Array.from({length:120000},(_,i)=>({cellName:`CELL_${i%1500}`,date:'2026-11-09',value:50+i%100}));
// Previous implementation, on observed numeric input where both algorithms must agree.
function previous(data,key){
  const grouped=data.reduce((groups,row)=>{const value=row[key];if(!value)return groups;if(!groups[value])groups[value]={items:[],...row};groups[value].items.push(row);return groups;},{});
  return Object.entries(grouped).map(([value,group])=>({...group,[key]:value,value:group.items.reduce((sum,row)=>sum+(Number(row.value)||0),0)/group.items.length,count:group.items.length,items:undefined}));
}
function measured(fn){fn(rows,'cellName');const samples=[];let result;for(let i=0;i<7;i++){const start=performance.now();result=fn(rows,'cellName');samples.push(performance.now()-start);}return {result,medianMs:[...samples].sort((a,b)=>a-b)[3],samplesMs:samples};}
const before=measured(previous),after=measured(aggregateChartRows);
assert.deepEqual(after.result,before.result);
const report={synthetic:true,observations:rows.length,groups:after.result.length,equivalentObservedNumericOutputs:true,previousMedianMs:before.medianMs,onePassMedianMs:after.medianMs,previousSamplesMs:before.samplesMs,onePassSamplesMs:after.samplesMs,note:'Isolated aggregation benchmark. Not a browser interaction, memory peak, API latency or end-to-end speed measurement.'};
writeFileSync(new URL('../reports/performance/analytics-aggregation-benchmark.json',import.meta.url),JSON.stringify(report,null,2));
console.log(JSON.stringify(report));
