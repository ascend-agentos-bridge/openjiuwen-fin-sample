import ch.qos.logback.classic.*;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import org.slf4j.LoggerFactory;
import java.util.*;

/** Records only SDK Hint construction and KVC warnings, never whole HTTP requests. */
final class BenchmarkSdkLog extends AppenderBase<ILoggingEvent> implements AutoCloseable {
 private final AgentHintBenchmark.Sink sink;private final List<Logger> loggers=new ArrayList<>();
 BenchmarkSdkLog(AgentHintBenchmark.Sink sink){this.sink=sink;setContext((LoggerContext)LoggerFactory.getILoggerFactory());start();for(String name:List.of("llm","com.openjiuwen.core.kvcache")){Logger l=(Logger)LoggerFactory.getLogger(name);l.setLevel(Level.INFO);l.setAdditive(false);l.addAppender(this);loggers.add(l);}}
 protected void append(ILoggingEvent e){String message=e.getFormattedMessage();if(message.contains("agent_hint sent:")||message.toLowerCase(Locale.ROOT).contains("kvc"))sink.line("sdk.log",Map.of("time",e.getTimeStamp(),"source","sdk_log","meaning","constructed_or_warning_not_server_execution","message",AgentHintBenchmark.safe(message)));}
 public void close(){for(Logger l:loggers){l.detachAppender(this);l.setLevel(Level.OFF);}stop();}
}
