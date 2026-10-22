package dev.portfolio.settlement;
import java.nio.file.*;
import java.math.*;
import java.util.*;
import java.util.concurrent.*;
import static dev.portfolio.store.SnapshotStore.*;

public class AcceptanceChecks {
 @SuppressWarnings("unchecked") static Map<String,Object> obj(Object x){return (Map<String,Object>)x;}
 @SuppressWarnings("unchecked") static List<Map<String,Object>> list(Object x){return (List<Map<String,Object>>)x;}
 static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
 static void fails(Runnable fn){try{fn.run();}catch(RuntimeException expected){return;}throw new AssertionError("expected rejection");}
 static String billId(Object x){return obj(obj(x).get("import")).get("ID").toString();}
 static String batchId(Object x){return obj(obj(x).get("batch")).get("ID").toString();}
 public static void main(String[] args)throws Exception{
  var directory=Files.createTempDirectory(Files.createDirectories(Path.of("target/test-results")),"settlement-check-");
  try(var service=new SettlementService(directory.toString())){
   check(SettlementService.commission(new BigDecimal("0.05"),new BigDecimal("0.1")).equals(new BigDecimal("0.01")),"rounding");
   fails(()->SettlementService.money("1.001"));fails(()->SettlementService.money("-1"));
   service.seed();fails(()->new SettlementService(directory.toString()));
   var differences=obj(service.importBill("DIFF","order_id,amount\nORDER-001,100\nORDER-002,479\nEXTRA,10"));
   var kinds=new HashSet<>();for(var row:list(differences.get("rows")))kinds.add(row.get("RESULT"));
   check(kinds.equals(Set.of("MATCHED","AMOUNT_MISMATCH","SYSTEM_MISSING","EXTERNAL_MISSING")),"four reconciliation outcomes");
   String id=billId(service.importBill("PAY","order_id,amount\nORDER-001,100.00\nORDER-002,480.00"));
   check(id.equals(billId(service.importBill("PAY","order_id,amount\r\nORDER-002,480\r\nORDER-001,100"))),"normalized duplicate import");
   fails(()->service.importBill("PAY","order_id,amount\nORDER-001,99"));
   fails(()->service.importBill("DUP","order_id,amount\nORDER-001,100\nORDER-001,100"));
   String bid;
   try(var pool=Executors.newFixedThreadPool(8)){
    var jobs=new ArrayList<Callable<String>>();for(int i=0;i<12;i++)jobs.add(()->batchId(service.confirm(id)));
    var distinct=new HashSet<String>();for(var future:pool.invokeAll(jobs))distinct.add(future.get());
    check(distinct.size()==1,"concurrent confirmations return same batch");bid=distinct.iterator().next();
   }
   check(list(obj(service.batch(bid)).get("payments")).size()==2,"exactly two payments");
   fails(()->service.confirm(billId(differences)));
   var success=new SettlementService.PaymentResult("ORDER-001","SUCCESS",null);
   fails(()->service.callback(bid,"bad-atomic",List.of(success,new SettlementService.PaymentResult("OUTSIDE","FAILED","x"))));
   check(list(obj(service.batch(bid)).get("payments")).stream().allMatch(p->p.get("STATUS").equals("PENDING")),"invalid callback rolled back all mutations");
   service.callback(bid,"event-1",List.of(success));
   check(obj(obj(service.batch(bid)).get("batch")).get("STATUS").equals("PROCESSING"),"partial callback still processing");
   var failure=new SettlementService.PaymentResult("ORDER-002","FAILED","synthetic rejection");
   service.callback(bid,"event-2",List.of(failure));service.callback(bid,"event-2",List.of(failure));
   check(obj(obj(service.batch(bid)).get("batch")).get("STATUS").equals("PARTIAL_SUCCESS"),"partial success terminal state");
   fails(()->service.callback(bid,"event-2",List.of(success)));
   fails(()->service.callback(bid,"event-3",List.of(new SettlementService.PaymentResult("ORDER-001","FAILED",null))));
  }
  try(var restored=new SettlementService(directory.toString())){
   check(list(obj(restored.overview()).get("payments")).size()==2,"restart persisted payment details");
   check(list(obj(restored.overview()).get("callbacks")).size()==2,"rejected/duplicate callbacks did not add records");
  }
  // Independent success and full-failure cases.
  for(String status:List.of("SUCCESS","FAILED"))try(var service=new SettlementService(Files.createTempDirectory(Files.createDirectories(Path.of("target/test-results")),"terminal-check-").toString())){
   service.seed();String id=billId(service.importBill("TERMINAL","order_id,amount\nORDER-001,100"));String bid=batchId(service.confirm(id));
   var result=obj(service.callback(bid,"final",List.of(new SettlementService.PaymentResult("ORDER-001",status,null))));
   check(obj(result.get("batch")).get("STATUS").equals(status),"full terminal status "+status);
  }
  System.out.println("PASS settlement: reconciliation, decimal rounding, duplicate imports, concurrent confirm, cross-bill allocation, callback rollback/idempotency, three terminal outcomes, process lock, restart");
 }
}
