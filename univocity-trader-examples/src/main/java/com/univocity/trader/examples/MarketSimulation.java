package com.univocity.trader.examples;

import com.univocity.trader.account.*;
import com.univocity.trader.config.*;
import com.univocity.trader.exchange.binance.*;
import com.univocity.trader.notification.*;

import java.time.*;

/**
 * @author uniVocity Software Pty Ltd - <a href="mailto:dev@univocity.com">dev@univocity.com</a>
 */
public class MarketSimulation {
	public static void main(String... args) {
		Binance.Simulator simulator = Binance.simulator();

//		TODO: configure your database connection as needed. By default MySQL will be used
//		simulator.configure().database()
//				.jdbcDriver("my.database.DriverClass")
//				.jdbcUrl("jdbc:mydb://localhost:5555/database")
//				.user("admin")
//				.password("qwerty");

		//you can test with one or more accounts at the same time
		Account account = simulator.configure().account();

		account
				.referenceCurrency("USDT") //Balances will be calculated using the reference currency.
				.tradeWith("BTC", "ADA", "LTC", "XRP", "ETH")
				.minimumInvestmentAmountPerTrade(10.0)
				.maximumInvestmentAmountPerTrade(250.0)
//				.maximumInvestmentPercentagePerAsset(30.0, "ADA", "ETH")
//				.maximumInvestmentPercentagePerAsset(50.0, "BTC", "LTC")
//				.maximumInvestmentAmountPerAsset(200, "XRP")
		;

		account.strategies()
				.add(ExampleStrategy::new);

		account.monitors()
				.add(ExampleStrategyMonitor::new);

		account.listeners()
				.add(new OrderExecutionToLog())
				.add(new SimpleStrategyStatistics())
		;

		Simulation simulation = simulator.configure().simulation();
		simulation.initialFunds(1000.0)
				.tradingFees(SimpleTradingFees.percentage(0.1))
				.emulateSlippage()
				.simulateFrom(LocalDate.of(2018, 7, 1).atStartOfDay())
				.simulateTo(LocalDate.of(2019, 7, 1).atStartOfDay());

//		execute simulation
		simulator.run();
	}
}
