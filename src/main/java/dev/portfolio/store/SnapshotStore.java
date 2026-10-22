package dev.portfolio.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.Function;

/** A bounded, single-process demo store. Each write runs on a copy; only a fully
 * validated snapshot is atomically published. A process lock prevents split-brain.
 * Not a database replacement: no distributed transactions, migration or WAL. */
public final class SnapshotStore implements AutoCloseable {
 private final ObjectMapper json=new ObjectMapper();
 private final Path file; private final FileChannel lockChannel; private final FileLock lock;
 private Map<String,Object> state;
 private final TypeReference<LinkedHashMap<String,Object>> type=new TypeReference<>(){};
 public SnapshotStore(Path directory) {
  FileChannel channel=null;FileLock acquired=null;
  try {
   Files.createDirectories(directory);file=directory.resolve("state.json");
   channel=FileChannel.open(directory.resolve("process.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);
   acquired=channel.tryLock();if(acquired==null)throw new IllegalStateException("data directory already in use");
   state=Files.exists(file)?json.readValue(Files.readAllBytes(file),type):new LinkedHashMap<>();
   lockChannel=channel;lock=acquired;
  } catch(Exception e) {
   try{if(acquired!=null)acquired.release();if(channel!=null)channel.close();}catch(IOException ignored){}
   throw new IllegalStateException("cannot open snapshot store",e);
  }
 }
 private Map<String,Object> copy(Map<String,Object> input) {
  return json.convertValue(input,type);
 }
 public synchronized <T> T read(Function<Map<String,Object>,T> operation) {return operation.apply(copy(state));}
 public synchronized <T> T write(Function<Map<String,Object>,T> operation) {
  var next=copy(state);T result=operation.apply(next);
  Path temporary=file.resolveSibling("state.json.tmp");
  try {
   byte[] bytes=json.writerWithDefaultPrettyPrinter().writeValueAsBytes(next);
   try(var channel=FileChannel.open(temporary,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING,StandardOpenOption.WRITE)) {
    var buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining())channel.write(buffer);channel.force(true);
   }
   Files.move(temporary,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
   state=next;return result;
  } catch(IOException e) {throw new IllegalStateException("snapshot commit failed; memory state unchanged",e);}
 }
 @SuppressWarnings("unchecked") public static List<Map<String,Object>> rows(Map<String,Object> s,String key){return (List<Map<String,Object>>)s.computeIfAbsent(key,k->new ArrayList<>());}
 public static Map<String,Object> map(Object... entries) {var r=new LinkedHashMap<String,Object>();for(int i=0;i<entries.length;i+=2)r.put((String)entries[i],entries[i+1]);return r;}
 public static Map<String,Object> require(List<Map<String,Object>> rows,String key,String id) {return rows.stream().filter(r->Objects.equals(r.get(key),id)).findFirst().orElseThrow(()->new NoSuchElementException("record not found: "+id));}
 @Override public synchronized void close(){try{lock.release();lockChannel.close();}catch(IOException e){throw new IllegalStateException(e);}}
}
