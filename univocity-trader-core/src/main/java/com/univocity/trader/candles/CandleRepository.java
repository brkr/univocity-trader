package com.univocity.trader.candles;

import com.univocity.trader.*;
import com.univocity.trader.config.*;
import com.univocity.trader.indicators.base.*;
import org.apache.commons.lang3.*;
import org.slf4j.*;
import org.springframework.jdbc.core.*;

import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

import static com.univocity.trader.candles.Candle.*;

public class CandleRepository {
	private static final Logger log = LoggerFactory.getLogger(CandleRepository.class);
	private static final String INSERT = "INSERT INTO candle VALUES (?,?,?,?,?,?,?,?)";
	private static final RowMapper<Candle> CANDLE_MAPPER = (rs, rowNum) -> {
		Candle out = new Candle(
				rs.getLong(1),
				rs.getLong(2),
				rs.getDouble(3),
				rs.getDouble(4),
				rs.getDouble(5),
				rs.getDouble(6),
				rs.getDouble(7));
		return out;
	};

	private final ConcurrentHashMap<String, Collection<Candle>> cachedResults = new ConcurrentHashMap<>();
	private final ThreadLocal<JdbcTemplate> db;

	public CandleRepository(DatabaseConfiguration config) {
		this.db = ThreadLocal.withInitial(() -> new JdbcTemplate(config.dataSource()));
	}

	public JdbcTemplate db() {
		return db.get();
	}

	private String buildCandleQuery(String symbol) {
		return "SELECT open_time, close_time, open, high, low, close, volume FROM candle WHERE symbol = '" + symbol + "'";
	}

	private PreparedStatement prepareInsert(PreparedStatement ps, String symbol, PreciseCandle tick) throws SQLException {
		ps.setObject(1, symbol);
		ps.setObject(2, tick.openTime);
		ps.setObject(3, tick.closeTime);
		ps.setObject(4, tick.open);
		ps.setObject(5, tick.high);
		ps.setObject(6, tick.low);
		ps.setObject(7, tick.close);
		ps.setObject(8, tick.volume);
		return ps;
	}

	private final ConcurrentHashMap<String, long[]> recentCandles = new ConcurrentHashMap<>();

	public boolean addToHistory(String symbol, PreciseCandle tick, boolean initializing) {
		try {
			long[] times = recentCandles.get(symbol);
			if (times != null && times[0] == tick.openTime && times[1] == tick.closeTime) {
				return false; //duplicate, skip
			} else {
				if (times == null) {
					times = new long[2];
					recentCandles.put(symbol, times);
				}
				times[0] = tick.openTime;
				times[1] = tick.closeTime;
			}

			if (db().execute(INSERT, (PreparedStatementCallback<Integer>) ps -> prepareInsert(ps, symbol, tick).executeUpdate()) == 0) {
				log.warn("Could not persist " + symbol + " Tick: " + tick);
				return false;
			}
		} catch (Exception ex) {
			if (ex.getMessage().contains("Duplicate entry")) {
				if (!initializing) {
					log.error("Skipping duplicate " + symbol + " Tick: " + tick);
				}
			} else {
				log.error("Error persisting " + symbol + " Tick: " + tick, ex);
			}
			return false;
		}
		return true;
	}

	private Enumeration<Candle> toEnumeration(ArrayBlockingQueue<Candle> queue, boolean[] ended) {
		return new Enumeration<>() {
			@Override
			public boolean hasMoreElements() {
				return !ended[0] || !queue.isEmpty();
			}

			@Override
			public Candle nextElement() {
				while (hasMoreElements()) {
					Candle next = null;
					try {
						next = queue.poll(100L, TimeUnit.MILLISECONDS);
					} catch (InterruptedException e) {
						log.error("Candle reading process interrupted", e);
						Thread.currentThread().interrupt();
					}
					if (next != null) {
						return next;
					}
				}
				return null;
			}
		};

	}

	private Enumeration<Candle> executeQuery(String symbol, String query, Collection<Candle> out) {
		boolean[] ended = new boolean[]{false};

		final long start = System.currentTimeMillis();
		Runnable readingProcess = () -> {
			Thread.currentThread().setName(symbol + " candle reader");
			log.debug("Executing SQL query: [{}]", query);
			int count = 0;

			try (Connection c = db().getDataSource().getConnection();
				 PreparedStatement s = c.prepareStatement(query);
				 ResultSet rs = s.executeQuery()) {

				while (rs.next()) {
					Candle candle = CANDLE_MAPPER.mapRow(rs, 0);
					out.add(candle);
					count++;
				}
			} catch (SQLException e) {
				log.error("Error reading " + symbol + " Candle from db().", e);
			} finally {
				log.trace("Read all {} candles of {} in {} seconds", count, symbol, (System.currentTimeMillis() - start) / 1000.0);
				ended[0] = true;
			}
		};

		if (out instanceof ArrayBlockingQueue) {
			new Thread(readingProcess).start();
			return toEnumeration((ArrayBlockingQueue<Candle>) out, ended);
		} else {
			readingProcess.run();
			cachedResults.put(symbol, out);
			return Collections.enumeration(out);
		}
	}

	public void clearCaches() {
		cachedResults.clear();
	}

	public void evictFromCache(String symbol) {
		log.trace("Evicting cached candles of {}", symbol);
		Collection<Candle> candles = cachedResults.remove(symbol);
		if (candles != null) {
			candles.clear();
		}
	}

	public String narrowQueryToTimeInterval(String query, Long from, Long to) {
		if (from != null || to != null) {
			query += " AND ";

			if (from != null) {
				query += "open_time >= " + from;
			}

			if (to != null) {
				if (from != null) {
					query += " AND ";
				}
				query += "close_time <= " + to;
			}
		}
		return query;
	}

	public String narrowQueryToTimeInterval(String query, Instant from, Instant to) {
		return narrowQueryToTimeInterval(query, from == null ? null : from.toEpochMilli(), to == null ? null : to.toEpochMilli());
	}

	public Enumeration<Candle> iterate(String symbol, Instant from, Instant to, boolean cache) {
		String query = buildCandleQuery(symbol);
		query = narrowQueryToTimeInterval(query, from, to);
		query += " ORDER BY open_time";

		Collection<Candle> out;
		long cacheSize = 0;
		if (cache) {
			Collection<Candle> cachedResult;
			boolean waitForCache;
			synchronized (cachedResults) {
				cachedResult = cachedResults.get(symbol);
				if (cachedResult != null) {
					waitForCache = true;
				} else {
					cachedResults.put(symbol, Collections.emptyList());
					waitForCache = false;
				}
			}

			if (waitForCache) {
				while (cachedResult == Collections.EMPTY_LIST) {
					try {
						Thread.sleep(100);
						cachedResult = cachedResults.get(symbol);
					} catch (InterruptedException e) {
						log.error("Error waiting for cached result of query " + query, e);
						clearCaches();
						Thread.currentThread().interrupt();
					}
				}
				return Collections.enumeration(cachedResult);
			} else {
				cacheSize = countCandles(symbol, from, to);
				out = new ArrayList<>((int) cacheSize);
			}
		} else {
			out = new ArrayBlockingQueue<>(5000) {
				public boolean add(Candle e) {
					try {
						super.put(e);
					} catch (InterruptedException ex) {
						log.error("Candle loading process interrupted", ex);
						Thread.currentThread().interrupt();
						return false;
					}
					return true;
				}
			};
		}

		return executeQuery(symbol, query, out);
	}

	public <T> void fillHistoryGaps(Exchange<T, ?> exchange, String symbol, Instant from, TimeInterval minGap) {
		log.info("Looking for gaps in history of {} from {}", symbol, getFormattedDateTimeWithYear(from.toEpochMilli()));

		List<T> ticks = exchange.getLatestTicks(symbol, minGap);
		for (T tick : ticks) {
			PreciseCandle candle = exchange.generatePreciseCandle(tick);
			addToHistory(symbol, candle, true);
		}

		List<long[]> gaps = new ArrayList<>();

		long previous = from == null ? -1 : from.toEpochMilli();

		Enumeration<Candle> result = iterate(symbol, from, Instant.now(), false);
		while (result.hasMoreElements()) {
			Candle candle = result.nextElement();
			if (candle == null) {
				break;
			}

			long minute = candle.openTime;
			if (previous == -1) {
				previous = minute;
				continue;
			}
			final long gapStart = previous;
			long gap = minute - previous;
			if (gap > minGap.ms) {
				long limit = gap / minGap.ms;
				do {
					long start = previous;
					long end = minute;

					limit -= 1000;
					if (limit > 0) {
						end = start + (1000L * minGap.ms);
					}
					gaps.add(new long[]{start, end});
					previous = end;
				} while (limit > 0);
				log.warn("Historical data of {} has a gap of {} minutes between {} and {}", symbol, (gap / minGap.ms), getFormattedDateTimeWithYear(gapStart), getFormattedDateTimeWithYear(minute));
			}
			previous = minute;
		}

//		settings.removeIgnoredIntervals(gaps);

		if (!gaps.isEmpty()) {
//			if (gaps.size() > 30) {
//				log.warn("Too many gaps in history: {}. Will process the 30 most recent and ignore older transactions", gaps.size());
//				gaps = gaps.subList(gaps.size() - 30, gaps.size());
//			}
			log.info("Filling {} gaps in history of {}", gaps.size(), symbol);

			Collections.reverse(gaps);

			int noDataCount = 0;
			for (long[] gap : gaps) {
				long start = gap[0];
				long end = gap[1];

				if (noDataCount > 20) {
					log.info("Aborting gap filling of {} as there is no data before {}", symbol, getFormattedDateTimeWithYear(start));
					return;
				}

				if (isKnownGap(symbol, start, end)) {
					noDataCount++;
					continue;
				}
				try {
					ticks = exchange.getHistoricalTicks(symbol.toUpperCase(), minGap, start, end);
					if (ticks.size() <= 2) {
						noDataCount++;
//						log.info("No Candles found for {} between {} and {}", symbol, getFormattedDateTimeWithYear(start), getFormattedDateTimeWithYear(end));
						log.warn("Found a historical gap between {} and {}. Interval blacklisted.", getFormattedDateTimeWithYear(start), getFormattedDateTimeWithYear(end));
						addGap(symbol, start, end);
					} else {
						log.info("Loaded {} {} Candles between {} and {}", ticks.size(), symbol, getFormattedDateTimeWithYear(start), getFormattedDateTimeWithYear(end));
						noDataCount = 0;
						for (T tick : ticks) {
							PreciseCandle candle = exchange.generatePreciseCandle(tick);
							addToHistory(symbol, candle, true);
						}
					}

					Thread.sleep(200);
				} catch (Exception e) {
					log.error("Error retrieving history between {} and {}", start, end);
				}
			}
		}
	}

	private long count(String query, Object... params) {
		Long result = db().queryForObject(query, params, Long.class);
		if (result == null) {
			return 0;
		}
		return result;
	}

	private boolean isKnownGap(String symbol, long start, long end) {
		return count("SELECT COUNT(*) FROM gap WHERE symbol = ? AND open_time = ? AND close_time = ?", symbol.toUpperCase(), start, end) > 0;
	}

	private void addGap(String symbol, long start, long end) {
		try {
			db().update("INSERT INTO gap VALUES (?,?,?)", symbol.toUpperCase(), start, end);
		} catch (Exception e) {
			log.error("Error persisting gap details: " + StringUtils.join(symbol, start, end), e);
		}
	}


	public Set<String> getKnownSymbols() {
		return new TreeSet<>(db().queryForList("SELECT DISTINCT symbol FROM candle", String.class));
	}

	public long countCandles(String symbol, Instant from, Instant to) {
		String query = "SELECT COUNT(*) FROM candle WHERE symbol = ?";
		query = narrowQueryToTimeInterval(query, from, to);
		return count(query, symbol);
	}

	public long countCandles(String symbol) {
		return countCandles(symbol, null, null);
	}

	public Candle lastCandle(String symbol) {
		String query = buildCandleQuery(symbol);
		query += " ORDER BY close_time DESC LIMIT 1";
		return db().queryForObject(query, CANDLE_MAPPER);
	}


}
