package dev.portfolio.settlement;
import java.nio.file.*;
import java.util.*;
import com.fasterxml.jackson.databind.*;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static dev.portfolio.store.SnapshotStore.*;

public class WebAcceptanceChecks {
 static JsonNode request(MockMvc mvc,String path,Object body,int expected)throws Exception{
  var json=new ObjectMapper();var result=mvc.perform(post(path).contentType("application/json").content(json.writeValueAsBytes(body))).andReturn();
  if(result.getResponse().getStatus()!=expected)throw new AssertionError(path+" returned "+result.getResponse().getStatus()+": "+result.getResponse().getContentAsString());
  return json.readTree(result.getResponse().getContentAsByteArray());
 }
 public static void main(String[] args)throws Exception{
  var directory=Files.createTempDirectory(Files.createDirectories(Path.of("target/test-results")),"mvc-settlement-");
  try(var s=new SettlementService(directory.toString())){
   var mvc=MockMvcBuilders.standaloneSetup(new Api(s)).build();request(mvc,"/api/seed",map(),200);
   var imported=request(mvc,"/api/imports",map("billRef","MVC","csv","order_id,amount\nORDER-001,100\nORDER-002,480"),200);
   var id=imported.get("import").get("ID").asText();
   var batch=request(mvc,"/api/imports/"+id+"/confirm",map(),200);var bid=batch.get("batch").get("ID").asText();
   var callback=map("eventId","mvc-event","results",List.of(map("orderId","ORDER-001","status","SUCCESS","reason",null),map("orderId","ORDER-002","status","FAILED","reason","test")));
   var result=request(mvc,"/api/batches/"+bid+"/results",callback,200);
   if(!result.get("batch").get("STATUS").asText().equals("PARTIAL_SUCCESS"))throw new AssertionError("JSON binding/result serialization");
   request(mvc,"/api/batches/"+bid+"/results",callback,200);
   request(mvc,"/api/imports",map("billRef","MVC","csv","order_id,amount\nORDER-001,99"),409);
   request(mvc,"/api/imports",map("billRef","BAD","csv","wrong header"),400);
   request(mvc,"/api/imports/missing/confirm",map(),404);
  }
  System.out.println("PASS settlement MVC: routes, JSON binding, status aggregation, 400/404/409");
 }
}
