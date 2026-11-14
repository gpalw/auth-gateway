'use strict';
const byId=id=>document.getElementById(id), normalize=row=>Object.fromEntries(Object.entries(row).map(([key,value])=>[key.toLowerCase(),value]));
const usd=value=>Number(value||0).toFixed(8), cell=(row,text)=>{const td=document.createElement('td');td.textContent=String(text??'—');row.append(td);};
function rows(id,values,columns){const body=byId(id);body.replaceChildren();for(const value of values){const tr=document.createElement('tr');for(const col of columns)cell(tr,col(value));body.append(tr);}}
function clear(){byId('results').hidden=true;for(const id of ['keys','daily','recent'])byId(id).replaceChildren();byId('token').value='';byId('status').textContent='View cleared. Credentials are never saved in browser storage.';}
byId('forget').addEventListener('click',clear);
byId('connect').addEventListener('submit',async event=>{
  event.preventDefault();const token=byId('token').value;byId('token').value='';byId('status').textContent='Loading private usage…';
  try{
    const get=async path=>{const response=await fetch(path,{headers:{Authorization:'Bearer '+token},cache:'no-store',credentials:'omit'});if(!response.ok)throw new Error('Unable to load private data. Check the token and direct loopback connection.');return response.json();};
    const [rawKeys,usage]=await Promise.all([get('/admin/keys'),get('/admin/usage')]);
    const keys=rawKeys.map(normalize), daily=usage.daily.map(normalize), recent=usage.requests.map(normalize), monthly=usage.monthly.map(normalize);
    const prefixes=new Map(keys.map(key=>[key.id,key.prefix]));
    byId('cost').textContent=usd(monthly.reduce((sum,r)=>sum+Number(r.cost||0),0));byId('held').textContent=usd(monthly.reduce((sum,r)=>sum+Number(r.held||0),0));byId('requests').textContent=monthly.reduce((sum,r)=>sum+Number(r.requests||0),0);
    rows('keys',keys,[r=>r.name+' / '+r.prefix,r=>r.models,r=>r.revoked?'Revoked':r.expires_at && Date.parse(r.expires_at)<=Date.now()?'Expired':'Enabled',r=>usd(r.monthly_budget)+' / '+usd(r.total_budget),r=>r.rpm+' / '+r.max_concurrent]);
    rows('daily',daily,[r=>r.usage_day,r=>prefixes.get(r.key_id)||r.key_id,r=>r.requests,r=>(r.input_tokens||0)+' / '+(r.output_tokens||0)+' / '+(r.cached_tokens||0),r=>usd(r.cost),r=>usd(r.held)]);
    rows('recent',recent,[r=>r.created_at,r=>(prefixes.get(r.key_id)||r.key_id)+' / '+r.model,r=>r.status+(r.error_category?' / '+r.error_category:''),r=>usd(r.cost),r=>usd(r.held)]);
    byId('results').hidden=false;byId('status').textContent='Loaded at '+new Date().toISOString()+'. Re-enter the token to refresh.';
  }catch(error){clear();byId('status').textContent=error.message;}
});
