package net.zylklab.flink.sandbox.event_time_example.job;

import org.apache.flink.api.common.functions.FilterFunction;
import org.apache.flink.api.common.functions.MapFunction;

import org.apache.flink.api.java.functions.KeySelector;

import org.apache.flink.util.Collector;

import org.apache.flink.streaming.api.TimeCharacteristic;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.AssignerWithPeriodicWatermarks;
import org.apache.flink.streaming.api.windowing.time.Time;
import org.apache.flink.streaming.api.windowing.windows.TimeWindow;
import org.apache.flink.streaming.api.watermark.Watermark;
import org.apache.flink.streaming.api.functions.sink.SinkFunction;
import org.apache.flink.streaming.api.functions.windowing.WindowFunction;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.Date;
import java.util.Iterator;

import net.zylklab.flink.sandbox.cep_examples.pojo.GeoHashEvent;

public class EventTimeSlideWindowGeoHashSubJob {
	private static final Logger _log = LoggerFactory.getLogger(EventTimeSlideWindowGeoHashSubJob.class);
	private static final Time WINDOW_TIME_SIZE = Time.of(30, TimeUnit.SECONDS);
	private static final Time WINDOW_TIME_SLIDE = Time.of(30, TimeUnit.SECONDS);
	private static final Time ALLOWED_LATENESS_TIME = Time.of(30, TimeUnit.SECONDS);
	private static final long MAX_OUT_OF_ORDERNESS_MS = 0l;
	
	public static void main(String[] args) throws Exception {
		_log.info("Starting application");
		StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
		_log.info("Environment created.");
		EventTimeSlideWindowGeoHashSubJob w = new EventTimeSlideWindowGeoHashSubJob();
		env.setStreamTimeCharacteristic(TimeCharacteristic.EventTime);
		w.addJob(env);
		env.execute("EventTimeWindowGeoHasSubJob");
	}
	public void addJob(StreamExecutionEnvironment env) throws Exception {
		DataStream<GeoHashEvent> dataStream = env.socketTextStream("localhost", 9999).map(new MapFunction<String, GeoHashEvent>() {
			private static final long serialVersionUID = 2724248722149591844L;
			@Override
			public GeoHashEvent map(String value) throws Exception {
				String[] values = value.split(" ");
				GeoHashEvent record = null;
				if(values != null && values.length == 3) {
					record =  new GeoHashEvent();
					//timestamp geohas count
					//1565559955000 gbsuv 2
					record.setTimestamp(Long.parseLong(values[0]));
					record.setGeohash(values[1]);
					record.setTotalGPRSEvents(Integer.parseInt(values[2]));
				}
				return record;
			}
		}).filter(new FilterFunction<GeoHashEvent>() {
			private static final long serialVersionUID = -191514705055797924L;
			@Override
			public boolean filter(GeoHashEvent value) throws Exception {
				if(value == null)
					return false;
				else
					return true;
			}
		}).assignTimestampsAndWatermarks(new AssignerWithPeriodicWatermarks<GeoHashEvent>() {
			private static final long serialVersionUID = 1L;
			private final long maxOutOfOrderness = MAX_OUT_OF_ORDERNESS_MS;
			private long currentMaxTimestamp;
			
			@Override
			public long extractTimestamp(GeoHashEvent element, long previousElementTimestamp) {
				long timestamp = element.getTimestamp();
				if(timestamp - currentMaxTimestamp > ALLOWED_LATENESS_TIME.toMilliseconds()) { 
					_log.warn("The event is so far in the future ... posible unordered queue");
				}
				currentMaxTimestamp = Math.max(timestamp, currentMaxTimestamp);
				return timestamp;
			}
			@Override
			public Watermark getCurrentWatermark() {
				Watermark w = new Watermark(currentMaxTimestamp - maxOutOfOrderness);
				return w;
			}
		});
		
		
		dataStream.filter(new FilterFunction<GeoHashEvent>() {
			private static final long serialVersionUID = 1L;
			@Override
			public boolean filter(GeoHashEvent value) throws Exception {
				//_log.info(String.format("Record in (%s) -> %s", value.getTs(), new CDRPojo(value)));
				return true;
			}
		}).keyBy(new KeySelector<GeoHashEvent, String>() {
			private static final long serialVersionUID = 4026713945628697567L;
			@Override
			public String getKey(GeoHashEvent cdr) throws Exception {
				//_log.info("window key formed."+cdr.getNumOrigin());
				return cdr.getGeohash();
			}
		})
		.timeWindow(WINDOW_TIME_SIZE, WINDOW_TIME_SLIDE)
		.allowedLateness(ALLOWED_LATENESS_TIME)
		.apply(new WindowFunction<GeoHashEvent, List<GeoHashEvent>, String, TimeWindow>() {
			private static final long serialVersionUID = 6850448280789756471L;
			@Override
			public void apply(String key, TimeWindow window, Iterable<GeoHashEvent> input, Collector<List<GeoHashEvent>> out) throws Exception {
				Iterator<GeoHashEvent> iter = input.iterator();
				List<GeoHashEvent> records = new ArrayList<GeoHashEvent>();
				GeoHashEvent current = null;
				while(iter.hasNext()) {
					current = iter.next();
					records.add(current);
				}
				
				_log.info("window cdrs collected. "+records.size()+ " window "+new Date(window.getStart())+":"+new Date(window.getEnd()));
				out.collect(records);
			}

		})
		.addSink(new SinkFunction<List<GeoHashEvent>>() {
			private static final long serialVersionUID = 6456951224630272805L;
			@Override
			public void invoke(List<GeoHashEvent> batch) throws Exception {
				//do something
			}
		});
	}
}
