package com.parker.forex.indicators;

import java.awt.Color;

import com.dukascopy.api.IIndicators;
import com.dukascopy.api.Instrument;
import com.dukascopy.api.indicators.DoubleRangeDescription;
import com.dukascopy.api.indicators.IChartInstrumentsListener;
import com.dukascopy.api.indicators.IIndicator;
import com.dukascopy.api.indicators.IIndicatorContext;
import com.dukascopy.api.indicators.IIndicatorsProvider;
import com.dukascopy.api.indicators.IndicatorInfo;
import com.dukascopy.api.indicators.IndicatorResult;
import com.dukascopy.api.indicators.InputParameterInfo;
import com.dukascopy.api.indicators.IntegerListDescription;
import com.dukascopy.api.indicators.IntegerRangeDescription;
import com.dukascopy.api.indicators.OptInputDescription;
import com.dukascopy.api.indicators.OptInputParameterInfo;
import com.dukascopy.api.indicators.OutputParameterInfo;

/**
 * Creates a bollinger band indicator based on the price difference between two
 * currency pairs.
 */
public class PairBollingerBands implements IIndicator, IChartInstrumentsListener {

    private IIndicatorsProvider indicatorsProvider;
    private IIndicator movingAverage;
    private IIndicator stdDev;
    private IndicatorInfo indicatorInfo;

    private InputParameterInfo[] inputParameterInfos;
    private OutputParameterInfo[] outputParameterInfos;
    private OptInputParameterInfo[] optInputParameterInfos;

    private double[][] inputs = new double[2][];
    private double[][] outputs = new double[4][];

    public void onStart(IIndicatorContext context) {
        indicatorsProvider = context.getIndicatorsProvider();
        movingAverage = indicatorsProvider.getIndicator("MA");
        stdDev = indicatorsProvider.getIndicator("STDDEV");

        indicatorInfo = new IndicatorInfo("PAIRBBANDS", "Pair Difference Bollinger Bands", "Overlap Studies", false, false, false, 2, 5, 4);

        int[] maValues = new int[IIndicators.MaType.values().length];
        String[] maNames = new String[IIndicators.MaType.values().length];

        for (int i = 0; i < maValues.length; i++) {
            maValues[i] = i;
            maNames[i] = IIndicators.MaType.values()[i].name();
        }

        // Price inputs
        inputParameterInfos = new InputParameterInfo[] { new InputParameterInfo("First Price", InputParameterInfo.Type.DOUBLE),
                new InputParameterInfo("Second Price", InputParameterInfo.Type.DOUBLE) };

        // Optional Inputs
        optInputParameterInfos = new OptInputParameterInfo[] {
                new OptInputParameterInfo("First instrument", OptInputParameterInfo.Type.OTHER,
                        new IntegerListDescription(-1, new int[] { -1 }, new String[] { "" })),
                new OptInputParameterInfo("Second instrument", OptInputParameterInfo.Type.OTHER,
                        new IntegerListDescription(-1, new int[] { -1 }, new String[] { "" })),
                new OptInputParameterInfo("Intervals", OptInputParameterInfo.Type.OTHER, new IntegerRangeDescription(50, 2, 2000, 1)),
                new OptInputParameterInfo("Deviation Limit", OptInputParameterInfo.Type.OTHER, new DoubleRangeDescription(2, 0.01, 5, 0.01, 3)),
                new OptInputParameterInfo("MA Type", OptInputParameterInfo.Type.OTHER,
                        new IntegerListDescription(IIndicators.MaType.EMA.ordinal(), maValues, maNames)) };

        // Outputs
        outputParameterInfos = new OutputParameterInfo[] {
                new OutputParameterInfo("Price Difference", OutputParameterInfo.Type.DOUBLE, OutputParameterInfo.DrawingStyle.LINE) {
                    {
                        setColor(Color.BLACK);
                    }
                }, new OutputParameterInfo("Upper Band", OutputParameterInfo.Type.DOUBLE, OutputParameterInfo.DrawingStyle.DASH_LINE) {
                    {
                        setColor(Color.RED);
                    }
                }, new OutputParameterInfo("Middle Band", OutputParameterInfo.Type.DOUBLE, OutputParameterInfo.DrawingStyle.DASH_LINE) {
                    {
                        setColor(Color.GREEN);
                    }
                }, new OutputParameterInfo("Lower Band", OutputParameterInfo.Type.DOUBLE, OutputParameterInfo.DrawingStyle.DASH_LINE) {
                    {
                        setColor(Color.RED);
                    }
                } };

        onInstrumentsChanged(context.getChartInstruments());
        context.addChartInstrumentsListener(this);
    }

    @Override
    public void onInstrumentsChanged(Instrument[] chartInstr) {
        int masterValue = (chartInstr != null ? chartInstr[0].ordinal() : -1);
        String masterName = (chartInstr != null ? chartInstr[0].name() : "");

        int[] slaveValues;
        String[] slaveNames;

        if ((chartInstr != null) && (chartInstr.length > 1)) {
            slaveValues = new int[chartInstr.length - 1];
            slaveNames = new String[chartInstr.length - 1];

            for (int i = 1; i < chartInstr.length; i++) {
                slaveValues[i - 1] = chartInstr[i].ordinal();
                slaveNames[i - 1] = chartInstr[i].name();
            }
        } else {
            slaveValues = new int[] { -1 };
            slaveNames = new String[] { "" };
        }

        optInputParameterInfos[0].setDescription(new IntegerListDescription(masterValue, new int[] { masterValue }, new String[] { masterName }));
        optInputParameterInfos[1].setDescription(new IntegerListDescription(slaveValues[0], slaveValues, slaveNames));
    }

    @Override
    public IndicatorResult calculate(int startIndex, int endIndex) {
        if (startIndex - getLookback() < 0) {
            startIndex -= startIndex - getLookback();
        }

        if (startIndex > endIndex) {
            return new IndicatorResult(0, 0);
        }

        if ((startIndex < inputs[0].length) && (startIndex < inputs[1].length)) {
            int maxLength = Math.max(inputs[0].length, inputs[1].length);
            int diffLength = Math.abs(inputs[0].length - inputs[1].length);

            double[][] inputs_ = new double[2][maxLength];
            double[][] outputs_ = new double[4][outputs[0].length - diffLength];

            System.arraycopy(inputs[0], 0, inputs_[0], maxLength - inputs[0].length, inputs[0].length);
            System.arraycopy(inputs[1], 0, inputs_[1], maxLength - inputs[1].length, inputs[1].length);

            // Price difference
            double[] priceDiff = new double[maxLength];
            for (int i = 0; i < maxLength; i++) {
                priceDiff[i] = inputs_[0][i] - inputs_[1][i];
            }

            // Moving average
            double[] maOutput = new double[maxLength];
            movingAverage.setInputParameter(0, priceDiff);
            movingAverage.setOutputParameter(0, maOutput);
            IndicatorResult maRes = movingAverage.calculate(startIndex + diffLength, endIndex);

            // Std deviation
            double[] stdDevOutput = new double[maxLength];
            stdDev.setInputParameter(0, priceDiff);
            stdDev.setOutputParameter(0, stdDevOutput);
            stdDev.calculate(startIndex + diffLength, endIndex);

            // Outputs
            int offset = maxLength - maRes.getNumberOfElements();
            for (int k = 0; k < maRes.getNumberOfElements(); k++) {
                outputs_[0][k] = priceDiff[offset + k];
                outputs_[1][k] = maOutput[k] + stdDevOutput[k];
                outputs_[2][k] = maOutput[k];
                outputs_[3][k] = maOutput[k] - stdDevOutput[k];
            }

            System.arraycopy(outputs_[0], 0, outputs[0], diffLength, outputs_[0].length);
            System.arraycopy(outputs_[1], 0, outputs[1], diffLength, outputs_[1].length);
            System.arraycopy(outputs_[2], 0, outputs[2], diffLength, outputs_[2].length);
            System.arraycopy(outputs_[3], 0, outputs[3], diffLength, outputs_[2].length);

            for (int i = 0; i < outputs.length; i++) {
                for (int j = 0; j < diffLength; j++) {
                    outputs[i][j] = Double.NaN;
                }
            }

        } else {
            // Data for second instrument isn't ready yet
            for (int i = 0; i < outputs.length; i++) {
                for (int j = 0; j < outputs[0].length; j++) {
                    outputs[i][j] = Double.NaN;
                }
            }
        }

        return new IndicatorResult(startIndex, outputs[0].length);
    }

    public IndicatorInfo getIndicatorInfo() {
        return indicatorInfo;
    }

    public InputParameterInfo getInputParameterInfo(int index) {
        if (index <= inputParameterInfos.length) {
            return inputParameterInfos[index];
        }
        return null;
    }

    public int getLookback() {
        return movingAverage.getLookback();
    }

    public int getLookforward() {
        return 0;
    }

    public OutputParameterInfo getOutputParameterInfo(int index) {
        if (index <= outputParameterInfos.length) {
            return outputParameterInfos[index];
        }
        return null;
    }

    public void setInputParameter(int index, Object array) {
        inputs[index] = (double[]) array;
    }

    public void setOutputParameter(int index, Object array) {
        outputs[index] = (double[]) array;
    }

    public OptInputParameterInfo getOptInputParameterInfo(int index) {
        if (index <= optInputParameterInfos.length) {
            return optInputParameterInfos[index];
        }
        return null;
    }

    public void setOptInputParameter(int index, Object value) {
        switch (index) {
        case 1:
            OptInputDescription descr = optInputParameterInfos[1].getDescription();
            int[] values = ((IntegerListDescription) descr).getValues();
            int instr = -1;

            for (int i = 0; i < values.length; i++) {
                if (values[i] == (Integer) value) {
                    instr = values[i];
                    break;
                }
            }

            if (instr < 0) {
                // value not found
                instr = values[0];
            }

            if (instr >= 0) {
                inputParameterInfos[1].setInstrument(Instrument.values()[instr]);
            } else {
                inputParameterInfos[1].setInstrument(null);
            }
            break;
        case 2:
            int intervals = (Integer) value;
            movingAverage.setOptInputParameter(0, intervals);
            stdDev.setOptInputParameter(0, intervals);
            break;
        case 3:
            double deviationLimit = (Double) value;
            stdDev.setOptInputParameter(1, deviationLimit);
            break;
        case 4:
            int maType = (Integer) value;
            movingAverage.setOptInputParameter(1, IIndicators.MaType.values()[maType].ordinal());
            break;
        }
    }
}