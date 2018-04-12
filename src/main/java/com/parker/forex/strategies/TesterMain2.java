/*
 * Copyright (c) 2009 Dukascopy (Suisse) SA. All Rights Reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * -Redistribution of source code must retain the above copyright notice, this
 *  list of conditions and the following disclaimer.
 *
 * -Redistribution in binary form must reproduce the above copyright notice,
 *  this list of conditions and the following disclaimer in the documentation
 *  and/or other materials provided with the distribution.
 * 
 * Neither the name of Dukascopy (Suisse) SA or the names of contributors may
 * be used to endorse or promote products derived from this software without
 * specific prior written permission.
 *
 * This software is provided "AS IS," without a warranty of any kind. ALL
 * EXPRESS OR IMPLIED CONDITIONS, REPRESENTATIONS AND WARRANTIES, INCLUDING
 * ANY IMPLIED WARRANTY OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE
 * OR NON-INFRINGEMENT, ARE HEREBY EXCLUDED. DUKASCOPY (SUISSE) SA ("DUKASCOPY")
 * AND ITS LICENSORS SHALL NOT BE LIABLE FOR ANY DAMAGES SUFFERED BY LICENSEE
 * AS A RESULT OF USING, MODIFYING OR DISTRIBUTING THIS SOFTWARE OR ITS
 * DERIVATIVES. IN NO EVENT WILL DUKASCOPY OR ITS LICENSORS BE LIABLE FOR ANY LOST
 * REVENUE, PROFIT OR DATA, OR FOR DIRECT, INDIRECT, SPECIAL, CONSEQUENTIAL,
 * INCIDENTAL OR PUNITIVE DAMAGES, HOWEVER CAUSED AND REGARDLESS OF THE THEORY
 * OF LIABILITY, ARISING OUT OF THE USE OF OR INABILITY TO USE THIS SOFTWARE,
 * EVEN IF DUKASCOPY HAS BEEN ADVISED OF THE POSSIBILITY OF SUCH DAMAGES.
 */
package com.parker.forex.strategies;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;
import java.util.TimeZone;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.dukascopy.api.Instrument;
import com.dukascopy.api.LoadingProgressListener;
import com.dukascopy.api.system.ISystemListener;
import com.dukascopy.api.system.ITesterClient;
import com.dukascopy.api.system.ITesterClient.DataLoadingMethod;
import com.dukascopy.api.system.TesterFactory;

/**
 * This small program demonstrates how to initialize Dukascopy tester and start
 * a strategy.
 */
public class TesterMain2 {

    private static final Logger LOGGER = LoggerFactory.getLogger(TesterMain2.class);

    // pxsmith@ail.com / pxSmith1!

    private static String jnlpUrl = "https://www.dukascopy.com/client/demo/jclient/jforex.jnlp";
    private static String userName = "DEMO2BVTQw";
    private static String password = "BVTQw";

    public static void main(String[] args) throws Exception {
        final ITesterClient client = TesterFactory.getDefaultInstance();

        client.setSystemListener(new ISystemListener() {
            @Override
            public void onStart(long processId) {
            }

            @Override
            public void onStop(long processId) {
                File reportFile = new File("report.html");
                try {
                    client.createReport(processId, reportFile);
                } catch (Exception e) {
                    LOGGER.error(e.getMessage(), e);
                }
                if (client.getStartedStrategies().size() == 0) {
                    System.exit(0);
                }
            }

            @Override
            public void onConnect() {
            }

            @Override
            public void onDisconnect() {
            }
        });

        LOGGER.info("Connecting...");
        client.connect(jnlpUrl, userName, password);

        // Wait for it to connect
        int i = 10;
        while (i > 0 && !client.isConnected()) {
            Thread.sleep(1000);
            i--;
        }
        if (!client.isConnected()) {
            LOGGER.error("Failed to connect Dukascopy servers.");
            System.exit(1);
        }

        LOGGER.info("Subscribing instruments...");
        Set<Instrument> instruments = new HashSet<>();
        instruments.add(Instrument.EURUSD);
        instruments.add(Instrument.USDJPY);
        instruments.add(Instrument.GBPUSD);
        
        instruments.add(Instrument.EURJPY);
        instruments.add(Instrument.EURGBP);
        instruments.add(Instrument.GBPJPY);

        // instruments.add(Instrument.EURCHF);
        // instruments.add(Instrument.CHFUSD);
        // instruments.add(Instrument.CHFJPY);
        // instruments.add(Instrument.GBPCHF);
        
        
        // instruments.add(Instrument.NZDCHF);
        // instruments.add(Instrument.CADCHF);
        // instruments.add(Instrument.AUDCAD);
        // instruments.add(Instrument.AUDJPY);
        // instruments.add(Instrument.AUDUSD);
        // instruments.add(Instrument.CADCHF);
        // instruments.add(Instrument.EURCAD);
        // instruments.add(Instrument.USDCAD);

        client.setSubscribedInstruments(instruments);
        client.setInitialDeposit(Instrument.AUDUSD.getPrimaryJFCurrency(), 7500);

        // Set the date range
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyyMMdd");
        dateFormat.setTimeZone(TimeZone.getTimeZone("GMT"));
        Date dateFrom = dateFormat.parse("20160105");
        Date dateTo = dateFormat.parse("20161231");

        client.setDataInterval(DataLoadingMethod.ALL_TICKS, dateFrom.getTime(), dateTo.getTime());

        LOGGER.info("Downloading data...");
        client.downloadData(null).get();

        // Start the strategy
        LOGGER.info("Starting strategy...");
        client.startStrategy(new TheCreeper(), new LoadingProgressListener() {
            @Override
            public void dataLoaded(long startTime, long endTime, long currentTime, String information) {
            }

            @Override
            public void loadingFinished(boolean allDataLoaded, long startTime, long endTime, long currentTime) {
            }

            @Override
            public boolean stopJob() {
                return false;
            }
        });
    }
}
