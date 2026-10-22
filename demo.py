import argparse,json
from urllib.request import Request,urlopen
from urllib.error import HTTPError,URLError

def call(base,path,body=None):
 data=None if body is None else json.dumps(body).encode('utf-8')
 request=Request(base+'/api'+path,data=data,headers={'Content-Type':'application/json'})
 try:
  with urlopen(request,timeout=10) as response:
   return json.loads(response.read()) if response.headers.get_content_type()=='application/json' else response.read()
 except HTTPError as exc:raise RuntimeError(f'HTTP {exc.code}: '+exc.read().decode('utf-8')) from exc
 except URLError as exc:raise RuntimeError('Start the local Java application first: '+str(exc)) from exc

def main():
 p=argparse.ArgumentParser();p.add_argument('--base',default='http://127.0.0.1:8081');args=p.parse_args();base=args.base
 call(base,'/seed',{})
 imported=call(base,'/imports',{'billRef':'API-DEMO','csv':'order_id,amount\nORDER-001,100.00\nORDER-002,480.00\nEXTRA,5.00'})
 id=imported['import']['ID'];print('Reconciliation:',json.dumps(imported,indent=2))
 batch=call(base,f'/imports/{id}/confirm',{});bid=batch['batch']['ID']
 again=call(base,f'/imports/{id}/confirm',{});assert bid==again['batch']['ID']
 payload={'eventId':'demo-'+bid,'results':[{'orderId':'ORDER-001','status':'SUCCESS','reason':None},{'orderId':'ORDER-002','status':'FAILED','reason':'synthetic rejection'}]}
 result=call(base,f'/batches/{bid}/results',payload);call(base,f'/batches/{bid}/results',payload)
 assert result['batch']['STATUS']=='PARTIAL_SUCCESS'
 print('Repeated confirm/callback are idempotent:',json.dumps(result,indent=2))
if __name__=='__main__':main()
