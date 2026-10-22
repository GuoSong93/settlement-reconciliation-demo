package dev.portfolio.settlement;
import java.math.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import jakarta.annotation.PreDestroy;
import dev.portfolio.store.SnapshotStore;
import static dev.portfolio.store.SnapshotStore.*;

@Service
public class SettlementService implements AutoCloseable {
 private final SnapshotStore store;
 public SettlementService(@Value("${demo.data-dir:./data}") String path){store=new SnapshotStore(Path.of(path));}
 public static BigDecimal money(String value) {
  try{var n=new BigDecimal(value).setScale(2,RoundingMode.UNNECESSARY);if(n.signum()<0||n.precision()>16)throw new IllegalArgumentException("amount out of range");return n;}
  catch(ArithmeticException|NumberFormatException e){throw new IllegalArgumentException("invalid money: "+value);}
 }
 public static BigDecimal commission(BigDecimal principal,BigDecimal rate){return principal.multiply(rate).setScale(2,RoundingMode.HALF_UP);}
 public static String digest(String text){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
 private void audit(Map<String,Object>s,String type,String detail){rows(s,"audit").add(map("event",type,"details",detail,"at",Instant.now().toString()));}
 public Object seed(){return store.write(s->{
  if(rows(s,"orders").isEmpty())for(int i=1;i<=3;i++){
   var principal=money(i==2?"6000":"1000");var rate=new BigDecimal(i==2?"0.0800":"0.1000");
   rows(s,"orders").add(map("ID","ORDER-00"+i,"PRINCIPAL",principal.toPlainString(),"RATE",rate.toPlainString(),"RULE_SNAPSHOT","v1: principal>=5000 -> 8%; otherwise 10%; HALF_UP per order","PAYABLE",commission(principal,rate).toPlainString()));
  }
  audit(s,"SEED","3 synthetic USD orders");return s;
 });}
 public Object importBill(String ref,String csv){
  if(ref==null||!ref.matches("[A-Za-z0-9_-]{1,64}"))throw new IllegalArgumentException("bill reference required (1-64 safe characters)");
  if(csv==null||csv.length()>200000)throw new IllegalArgumentException("CSV missing or too large");
  var lines=csv.strip().split("\\R");if(lines.length<2||!lines[0].strip().equals("order_id,amount"))throw new IllegalArgumentException("CSV header must be order_id,amount");
  var actual=new TreeMap<String,BigDecimal>();
  for(int i=1;i<lines.length;i++){
   var p=lines[i].split(",",-1);if(p.length!=2||!p[0].strip().matches("[A-Za-z0-9_-]{1,64}"))throw new IllegalArgumentException("invalid CSV line "+(i+1));
   if(actual.putIfAbsent(p[0].strip(),money(p[1].strip()))!=null)throw new IllegalArgumentException("duplicate CSV order");
  }
  var hash=digest(actual.toString());
  return store.write(s->{
   for(var old:rows(s,"imports"))if(old.get("BILL_REF").equals(ref)){
    if(!old.get("DIGEST").equals(hash))throw new IllegalStateException("bill reference reused with different content");
    return reconciliation(s,old.get("ID").toString());
   }
   if(rows(s,"orders").isEmpty())throw new IllegalStateException("seed orders first");
   var expected=new TreeMap<String,BigDecimal>();for(var o:rows(s,"orders"))expected.put(o.get("ID").toString(),money(o.get("PAYABLE").toString()));
   var id=UUID.randomUUID().toString();rows(s,"imports").add(map("ID",id,"BILL_REF",ref,"DIGEST",hash));
   var ids=new TreeSet<>(expected.keySet());ids.addAll(actual.keySet());
   for(var order:ids){var a=expected.get(order);var b=actual.get(order);
    String result=a==null?"SYSTEM_MISSING":b==null?"EXTERNAL_MISSING":a.compareTo(b)==0?"MATCHED":"AMOUNT_MISMATCH";
    rows(s,"reconciliation").add(map("IMPORT_ID",id,"ORDER_ID",order,"EXPECTED",a==null?null:a.toPlainString(),"ACTUAL",b==null?null:b.toPlainString(),"RESULT",result));
   }
   audit(s,"IMPORT",ref);return reconciliation(s,id);
  });
 }
 public Object confirm(String id){return store.write(s->{
  for(var old:rows(s,"batches"))if(old.get("IMPORT_ID").equals(id))return batch(s,old.get("ID").toString());
  require(rows(s,"imports"),"ID",id);
  var matched=rows(s,"reconciliation").stream().filter(r->r.get("IMPORT_ID").equals(id)&&r.get("RESULT").equals("MATCHED")).toList();
  if(matched.isEmpty())throw new IllegalStateException("no matched rows to settle");
  for(var r:matched)for(var p:rows(s,"payments"))if(p.get("ORDER_ID").equals(r.get("ORDER_ID")))throw new IllegalStateException("order already allocated to a batch");
  var bid=UUID.randomUUID().toString();rows(s,"batches").add(map("ID",bid,"IMPORT_ID",id,"STATUS","PENDING"));
  for(var r:matched)rows(s,"payments").add(map("BATCH_ID",bid,"ORDER_ID",r.get("ORDER_ID"),"AMOUNT",r.get("EXPECTED"),"STATUS","PENDING","REASON",null));
  audit(s,"CONFIRM_MATCHED_ONLY",bid+"; unmatched rows excluded");return batch(s,bid);
 });}
 public record PaymentResult(String orderId,String status,String reason){}
 public Object callback(String bid,String eventId,List<PaymentResult> results){
  if(eventId==null||!eventId.matches("[A-Za-z0-9_-]{1,80}")||results==null||results.isEmpty())throw new IllegalArgumentException("event id and results required");
  var canonical=new TreeMap<String,Object>();
  for(var r:results){
   if(r==null||r.orderId()==null||!("SUCCESS".equals(r.status())||"FAILED".equals(r.status()))||r.reason()!=null&&r.reason().length()>500)throw new IllegalArgumentException("invalid payment result");
   if(canonical.put(r.orderId(),map("status",r.status(),"reason",r.reason()))!=null)throw new IllegalArgumentException("duplicate result");
  }
  // JSON avoids delimiter ambiguities in idempotency fingerprints.
  String hash;try{hash=digest(bid+new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(canonical));}catch(Exception e){throw new IllegalStateException(e);}
  final String fingerprint=hash;
  return store.write(s->{
   var b=require(rows(s,"batches"),"ID",bid);
   for(var old:rows(s,"callbacks"))if(old.get("EVENT_ID").equals(eventId)){
    if(!old.get("DIGEST").equals(fingerprint))throw new IllegalStateException("event id reused with different payload");return batch(s,bid);
   }
   var payments=rows(s,"payments").stream().filter(p->p.get("BATCH_ID").equals(bid)).toList();
   for(var r:results){
    var p=require(payments,"ORDER_ID",r.orderId());
    if(!p.get("STATUS").equals("PENDING")){
     if(!p.get("STATUS").equals(r.status())||!Objects.equals(p.get("REASON"),r.reason()))throw new IllegalStateException("terminal result immutable");
    }else{p.put("STATUS",r.status());p.put("REASON",r.reason());}
   }
   long pending=payments.stream().filter(p->p.get("STATUS").equals("PENDING")).count(),good=payments.stream().filter(p->p.get("STATUS").equals("SUCCESS")).count(),bad=payments.stream().filter(p->p.get("STATUS").equals("FAILED")).count();
   b.put("STATUS",pending>0?"PROCESSING":bad==0?"SUCCESS":good==0?"FAILED":"PARTIAL_SUCCESS");
   rows(s,"callbacks").add(map("EVENT_ID",eventId,"DIGEST",fingerprint));audit(s,"PAYMENT_RESULT",bid+" -> "+b.get("STATUS"));return batch(s,bid);
  });
 }
 private Object reconciliation(Map<String,Object>s,String id){return map("import",require(rows(s,"imports"),"ID",id),"rows",rows(s,"reconciliation").stream().filter(r->r.get("IMPORT_ID").equals(id)).toList());}
 public Object reconciliation(String id){return store.read(s->reconciliation(s,id));}
 private Object batch(Map<String,Object>s,String id){return map("batch",require(rows(s,"batches"),"ID",id),"payments",rows(s,"payments").stream().filter(r->r.get("BATCH_ID").equals(id)).toList());}
 public Object batch(String id){return store.read(s->batch(s,id));}
 public Object overview(){return store.read(s->s);}
 @PreDestroy @Override public void close(){store.close();}
}
