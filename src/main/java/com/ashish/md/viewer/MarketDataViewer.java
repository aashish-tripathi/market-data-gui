package com.ashish.md.viewer;

import com.ashish.marketdata.avro.*;
import com.ashish.md.broker.EMSBroker;
import com.ashish.md.broker.KafkaBroker;
import javafx.animation.AnimationTimer;
import javafx.animation.FillTransition;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.beans.value.ChangeListener;
import javafx.collections.ObservableList;
import javafx.event.EventHandler;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.stage.Stage;
import javafx.util.Duration;
import org.apache.avro.io.DatumReader;
import org.apache.avro.io.Decoder;
import org.apache.avro.io.DecoderFactory;
import org.apache.avro.specific.SpecificDatumReader;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;

import javax.jms.Message;
import javax.jms.TextMessage;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class MarketDataViewer extends Application {

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage primaryStage) throws Exception {

        // market price stage
        Stage marketPriceStage = new Stage();
        viewMarketPrice(marketPriceStage);

        // market depth stage
        viewMarketDepth(primaryStage);

        // trade stage
        Stage tradeStage = new Stage();
        viewTrades(tradeStage);

        // Quote stage
        Stage quoteStage = new Stage();
        viewQuotes(quoteStage);
    }

    // market price setup
    private void viewMarketPrice(Stage stage) {
        stage.setX(600);
        stage.setY(100);
        stage.setWidth(400);
        stage.setHeight(400);
        GridPane grid = createGrid();
        Map<String, Label> cryptoLabels = createCryptoPriceLabels();
        addLabelsToGrid(cryptoLabels, grid);
        Rectangle background = createBackgroundRectangleWithAnimation(400, 500);

        StackPane root = new StackPane();
        root.getChildren().add(background);
        root.getChildren().add(grid);
        Scene marketPriceScene = new Scene(root, 400, 500);
        stage.setScene(marketPriceScene);

        MarketPriceContainer pricesContainer = new MarketPriceContainer();
        MarketPriceUpdater priceUpdater = new MarketPriceUpdater(pricesContainer, true);
        AnimationTimer renderMarketPrice = getAnimationTimerForMarketPrice(stage, cryptoLabels, pricesContainer);
        addWindowResizeListener(stage, background);
        renderMarketPrice.start();
        priceUpdater.start();
        stage.show();
    }

    private AnimationTimer getAnimationTimerForMarketPrice(Stage stage, Map<String, Label> marketPriceLabelsMap, MarketPriceContainer pricesContainer) {
        AnimationTimer renderMarketPrice = new AnimationTimer() {
            @Override
            public void handle(long now) {
                if (pricesContainer.getLockObject().tryLock()) {
                    try {
                        stage.setTitle("Market Price for " + String.valueOf(pricesContainer.getMarketPrice().getSymbol()));
                        Label symbol = marketPriceLabelsMap.get("Symbol");
                        symbol.setText(String.valueOf(pricesContainer.getMarketPrice().getSymbol()));

                        Label etherLabel = marketPriceLabelsMap.get("Exchange");
                        etherLabel.setText(String.valueOf(pricesContainer.getMarketPrice().getExchange()));

                        Label open = marketPriceLabelsMap.get("Open");
                        open.setText(String.valueOf(pricesContainer.getMarketPrice().getOpen()));

                        Label high = marketPriceLabelsMap.get("High");
                        high.setText(String.valueOf(pricesContainer.getMarketPrice().getHigh()));

                        Label low = marketPriceLabelsMap.get("Low");
                        low.setText(String.valueOf(pricesContainer.getMarketPrice().getLow()));

                        Label volume = marketPriceLabelsMap.get("Volume");
                        volume.setText(String.valueOf(pricesContainer.getMarketPrice().getVolume()));

                        Label lastTradePrice = marketPriceLabelsMap.get("LastTradePrice");
                        lastTradePrice.setText(String.valueOf(pricesContainer.getMarketPrice().getLastPrice()));

                        Label lastTradeSize = marketPriceLabelsMap.get("LastTradeSize");
                        lastTradeSize.setText(String.valueOf(pricesContainer.getMarketPrice().getLastTradeSize()));

                        Label lastTradeTime = marketPriceLabelsMap.get("LastTradeTime");
                        lastTradeTime.setText(String.valueOf(pricesContainer.getMarketPrice().getLastTradeTime()));

                        Label lowerCircuit = marketPriceLabelsMap.get("LowerCircuit");
                        lowerCircuit.setText(String.valueOf(pricesContainer.getMarketPrice().getLowerCircuit()));

                        Label upperCircuit = marketPriceLabelsMap.get("UpperCircuit");
                        upperCircuit.setText(String.valueOf(pricesContainer.getMarketPrice().getUperCircuit()));

                    } finally {
                        pricesContainer.getLockObject().unlock();
                    }
                }
            }
        };
        return renderMarketPrice;
    }

    public static class MarketPriceContainer {
        private Lock lockObject = new ReentrantLock();
        private MarketPrice marketPrice;

        public MarketPriceContainer() {
            this.marketPrice = new MarketPrice();
        }

        public Lock getLockObject() {
            return lockObject;
        }

        public MarketPrice getMarketPrice() {
            return marketPrice;
        }

        public void setMarketPrice(MarketPrice marketPrice) {
            this.marketPrice = marketPrice;
        }
    }

    public static class MarketPriceUpdater extends Thread {
        private MarketPriceContainer pricesContainer;
        private KafkaConsumer<String, String> kafkaConsumer;
        private EMSBroker emsBroker;
        private boolean kafka;

        public MarketPriceUpdater(MarketPriceContainer pricesContainer, boolean kafka) {
            this.pricesContainer = pricesContainer;
            this.kafka = kafka;
            try {
                if (!kafka) {
                    emsBroker = new EMSBroker("ashish-VirtualBox:7222", null, null);
                    emsBroker.createConsumer("exsim.nse.marketprice", true);
                } else {
                    this.kafkaConsumer = new KafkaBroker("ashish-VirtualBox:9093,ashish-VirtualBox:9094,ashish-VirtualBox:9095").createConsumer(null);
                    this.kafkaConsumer.subscribe(Arrays.asList("exsim.nse.marketprice"));
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        @Override
        public void run() {

            while (true) {
                pricesContainer.getLockObject().lock();
                try {
                    if (!kafka) {
                        Message msg = emsBroker.consumer().receive();
                        if (msg == null)
                            return;
                        if (msg instanceof TextMessage) {
                            TextMessage message = (TextMessage) msg;
                            byte[] decoded = Base64.getDecoder().decode(message.getText());
                            MarketPrice marketPrice = deSerealizeAvroHttpRequestJSON(decoded);
                            pricesContainer.setMarketPrice(marketPrice);
                        }
                    } else {
                        ConsumerRecords<String, String> records = kafkaConsumer.poll(java.time.Duration.ofMillis(10));
                        for (ConsumerRecord<String, String> record : records) {
                            String symbol = record.key();
                            String data = record.value();
                            byte[] decoded = Base64.getDecoder().decode(data);
                            {
                                MarketPrice marketPrice = deSerealizeAvroHttpRequestJSON(decoded);
                                pricesContainer.setMarketPrice(marketPrice);
                            }
                        }
                    }

                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    pricesContainer.getLockObject().unlock();
                }
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                }
            }
        }

        public MarketPrice deSerealizeAvroHttpRequestJSON(byte[] data) {
            DatumReader<MarketPrice> reader
                    = new SpecificDatumReader<>(MarketPrice.class);
            Decoder decoder = null;
            try {
                decoder = DecoderFactory.get().jsonDecoder(MarketPrice.getClassSchema(), new String(data));
                return reader.read(null, decoder);
            } catch (IOException e) {
                //logger.error("Deserialization error:" + e.getMessage());
            }
            return null;
        }
    }

    private Map<String, Label> createCryptoPriceLabels() {
        Label symbol = new Label("0");
        symbol.setId("Symbol");

        Label exchange = new Label("0");
        exchange.setId("Exchange");

        Label open = new Label("0");
        open.setId("Open");

        Label high = new Label("0");
        high.setId("High");

        Label low = new Label("0");
        low.setId("Low");

        Label volume = new Label("0");
        volume.setId("Volume");

        Label lastTradePrice = new Label("0");
        lastTradePrice.setId("LastTradePrice");

        Label lastTradeSize = new Label("0");
        lastTradeSize.setId("LastTradeSize");

        Label lastTradeTime = new Label("0");
        lastTradeTime.setId("LastTradeTime");

        Label lowerCircuit = new Label("0");
        lowerCircuit.setId("LowerCircuit");

        Label upperCircuit = new Label("0");
        upperCircuit.setId("UpperCircuit");

        Map<String, Label> linkedHashMap = new LinkedHashMap<>();
        linkedHashMap.put("Symbol", symbol);
        linkedHashMap.put("Exchange", exchange);
        linkedHashMap.put("Open", open);
        linkedHashMap.put("High", high);
        linkedHashMap.put("Low", low);
        linkedHashMap.put("Volume", volume);
        linkedHashMap.put("LastTradePrice", lastTradePrice);
        linkedHashMap.put("LastTradeSize", lastTradeSize);
        linkedHashMap.put("LastTradeTime", lastTradeTime);
        linkedHashMap.put("LowerCircuit", lowerCircuit);
        linkedHashMap.put("UpperCircuit", upperCircuit);

        return linkedHashMap;
    }

    private GridPane createGrid() {
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setAlignment(Pos.CENTER);
        return grid;
    }

    private void addLabelsToGrid(Map<String, Label> labels, GridPane grid) {
        int row = 0;
        for (Map.Entry<String, Label> entry : labels.entrySet()) {
            String cryptoName = entry.getKey();
            Label nameLabel = new Label(cryptoName);
            nameLabel.setTextFill(Color.BLUE);
            nameLabel.setOnMousePressed(event -> nameLabel.setTextFill(Color.RED));
            nameLabel.setOnMouseReleased((EventHandler) event -> nameLabel.setTextFill(Color.BLUE));

            grid.add(nameLabel, 0, row);
            grid.add(entry.getValue(), 1, row);
            row++;
        }
    }

    private Rectangle createBackgroundRectangleWithAnimation(double width, double height) {
        Rectangle backround = new Rectangle(width, height);
        FillTransition fillTransition = new FillTransition(Duration.millis(1000), backround, Color.LIGHTGREEN, Color.LIGHTBLUE);
        fillTransition.setCycleCount(Timeline.INDEFINITE);
        fillTransition.setAutoReverse(true);
        fillTransition.play();
        return backround;
    }

    // Market depth setup

    private void viewMarketDepth(Stage primaryStage) {
        TableView tableView = getMarketDepthTableView();
        VBox vbox = new VBox(tableView);
        Scene marketDepthScene = new Scene(vbox);
        primaryStage.setX(100);
        primaryStage.setY(100);
        primaryStage.setHeight(400);
        primaryStage.setWidth(500);
        primaryStage.setScene(marketDepthScene);
        MarketDepthContainer depthContainer = new MarketDepthContainer();
        MarketByPriceUpdater marketByPriceUpdater = new MarketByPriceUpdater(depthContainer, true);

        AnimationTimer renderMarketDepth = getAnimationTimerForMarketByPrice(primaryStage, tableView, depthContainer);
        renderMarketDepth.start();
        marketByPriceUpdater.start();
        primaryStage.show();
    }

    private AnimationTimer getAnimationTimerForMarketByPrice(Stage primaryStage, TableView tableView, MarketDepthContainer depthContainer) {
        AnimationTimer renderMarketDepth = new AnimationTimer() {
            @Override
            public void handle(long now) {
                if (depthContainer.getLockObject().tryLock()) {
                    try {
                        MarketByPrice marketByPrice = depthContainer.getMarketByPrice();
                        ObservableList observableList = tableView.getItems();
                        if (observableList.isEmpty()) {
                            tableView.getItems().add(new DepthData(0, 0, 0, 0, 0, 0));
                            tableView.getItems().add(new DepthData(0, 0, 0, 0, 0, 0));
                            tableView.getItems().add(new DepthData(0, 0, 0, 0, 0, 0));
                            tableView.getItems().add(new DepthData(0, 0, 0, 0, 0, 0));
                            tableView.getItems().add(new DepthData(0, 0, 0, 0, 0, 0));
                        } else {
                            if (marketByPrice.getBidList() != null && !marketByPrice.getBidList().isEmpty()) {
                                List<BidDepth> bidDepths = marketByPrice.getBidList();
                                List<AskDepth> askDepths = marketByPrice.getAskList();
                                String symbol = String.valueOf(marketByPrice.getSymbol());
                                primaryStage.setTitle("Market Depth for " + symbol);
                                if (bidDepths.size() > 4 && askDepths.size() > 4) {
                                    tableView.getItems().set(0, new DepthData(bidDepths.get(0).getBidPrice(), bidDepths.get(0).getBidSize(), 5,
                                            askDepths.get(0).getAskPrice(), askDepths.get(0).getAskSize(), 3));
                                    tableView.getItems().set(1, new DepthData(bidDepths.get(1).getBidPrice(), bidDepths.get(1).getBidSize(), 5,
                                            askDepths.get(1).getAskPrice(), askDepths.get(1).getAskSize(), 3));
                                    tableView.getItems().set(2, new DepthData(bidDepths.get(2).getBidPrice(), bidDepths.get(2).getBidSize(), 5,
                                            askDepths.get(2).getAskPrice(), askDepths.get(2).getAskSize(), 3));
                                    tableView.getItems().set(3, new DepthData(bidDepths.get(3).getBidPrice(), bidDepths.get(3).getBidSize(), 5,
                                            askDepths.get(3).getAskPrice(), askDepths.get(3).getAskSize(), 3));
                                    tableView.getItems().set(4, new DepthData(bidDepths.get(4).getBidPrice(), bidDepths.get(4).getBidSize(), 5,
                                            askDepths.get(4).getAskPrice(), askDepths.get(4).getAskSize(), 3));
                                }
                            }
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    } finally {
                        depthContainer.getLockObject().unlock();
                    }
                }
            }
        };
        return renderMarketDepth;
    }

    public static class DepthData {
        private double bid;
        private long bidQty;
        private int bidOrders;
        private double ask;
        private long askQty;
        private int askOrders;

        public DepthData(double bid, long bidQty, int bidOrders, double ask, long askQty, int askOrders) {
            this.bid = bid;
            this.bidQty = bidQty;
            this.bidOrders = bidOrders;
            this.ask = ask;
            this.askQty = askQty;
            this.askOrders = askOrders;
        }

        public double getBid() {
            return bid;
        }

        public void setBid(double bid) {
            this.bid = bid;
        }

        public long getBidQty() {
            return bidQty;
        }

        public void setBidQty(long bidQty) {
            this.bidQty = bidQty;
        }

        public int getBidOrders() {
            return bidOrders;
        }

        public void setBidOrders(int bidOrders) {
            this.bidOrders = bidOrders;
        }

        public double getAsk() {
            return ask;
        }

        public void setAsk(double ask) {
            this.ask = ask;
        }

        public long getAskQty() {
            return askQty;
        }

        public void setAskQty(long askQty) {
            this.askQty = askQty;
        }

        public int getAskOrders() {
            return askOrders;
        }

        public void setAskOrders(int askOrders) {
            this.askOrders = askOrders;
        }

        @Override
        public String toString() {
            return "Person{" +
                    "bid=" + bid +
                    ", bidQty=" + bidQty +
                    ", bidOrders=" + bidOrders +
                    ", ask=" + ask +
                    ", askQty=" + askQty +
                    ", askOrders=" + askOrders +
                    '}';
        }
    }

    public static class MarketDepthContainer {
        private Lock lockObject = new ReentrantLock();
        private MarketByPrice marketByPrice;

        public MarketDepthContainer() {
            this.marketByPrice = new MarketByPrice();
        }

        public Lock getLockObject() {
            return lockObject;
        }

        public MarketByPrice getMarketByPrice() {
            return marketByPrice;
        }

        public void setMarketByPrice(MarketByPrice marketByPrice) {
            this.marketByPrice = marketByPrice;
        }
    }

    public static class MarketByPriceUpdater extends Thread {
        private MarketDepthContainer marketDepthContainer;
        private KafkaConsumer<String, String> kafkaConsumer;
        private EMSBroker emsBroker;
        private boolean kafka;

        public MarketByPriceUpdater(MarketDepthContainer marketDepthContainer, boolean kafka) {
            this.marketDepthContainer = marketDepthContainer;
            this.kafka = kafka;
            try {
                if (!kafka) {
                    emsBroker = new EMSBroker("ashish-VirtualBox:7222", null, null);
                    emsBroker.createConsumer("exsim.nse.marketbyprice", true);
                } else {
                    this.kafkaConsumer = new KafkaBroker("ashish-VirtualBox:9093,ashish-VirtualBox:9094,ashish-VirtualBox:9095").createConsumer(null);
                    this.kafkaConsumer.subscribe(Arrays.asList("exsim.nse.marketbyprice"));
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        @Override
        public void run() {
            while (true) {
                marketDepthContainer.getLockObject().lock();
                try {
                    if (!kafka) {
                        Message msg = emsBroker.consumer().receive();
                        if (msg == null)
                            return;
                        if (msg instanceof TextMessage) {
                            TextMessage message = (TextMessage) msg;
                            byte[] decoded = Base64.getDecoder().decode(message.getText());
                            MarketByPrice marketByPrice = deSerealizeAvroHttpRequestJSON(decoded);
                            marketDepthContainer.setMarketByPrice(marketByPrice);
                        }
                    } else {
                        ConsumerRecords<String, String> records = kafkaConsumer.poll(java.time.Duration.ofMillis(10));
                        for (ConsumerRecord<String, String> record : records) {
                            String symbol = record.key();
                            String data = record.value();
                            byte[] decoded = Base64.getDecoder().decode(data);
                            {
                                MarketByPrice marketByPrice = deSerealizeAvroHttpRequestJSON(decoded);
                                marketDepthContainer.setMarketByPrice(marketByPrice);
                            }
                        }
                    }

                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    marketDepthContainer.getLockObject().unlock();
                }
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                }
            }
        }

        public MarketByPrice deSerealizeAvroHttpRequestJSON(byte[] data) {
            DatumReader<MarketByPrice> reader
                    = new SpecificDatumReader<>(MarketByPrice.class);
            Decoder decoder = null;
            try {
                decoder = DecoderFactory.get().jsonDecoder(MarketByPrice.getClassSchema(), new String(data));
                return reader.read(null, decoder);
            } catch (IOException e) {
                //logger.error("Deserialization error:" + e.getMessage());
            }
            return null;
        }
    }

    private TableView getMarketDepthTableView() {
        TableView tableView = new TableView();

        TableColumn<DepthData, String> column1 = new TableColumn<>("Bid");
        column1.setCellValueFactory(new PropertyValueFactory<>("bid"));

        TableColumn<DepthData, String> column2 = new TableColumn<>("Qty");
        column2.setCellValueFactory(new PropertyValueFactory<>("bidQty"));

        TableColumn<DepthData, String> column3 = new TableColumn<>("Orders");
        column3.setCellValueFactory(new PropertyValueFactory<>("bidOrders"));

        TableColumn<DepthData, String> column4 = new TableColumn<>("Ask");
        column4.setCellValueFactory(new PropertyValueFactory<>("ask"));

        TableColumn<DepthData, String> column5 = new TableColumn<>("Qty");
        column5.setCellValueFactory(new PropertyValueFactory<>("askQty"));

        TableColumn<DepthData, String> column6 = new TableColumn<>("Orders");
        column6.setCellValueFactory(new PropertyValueFactory<>("askOrders"));

        tableView.getColumns().add(column1);
        tableView.getColumns().add(column2);
        tableView.getColumns().add(column3);
        tableView.getColumns().add(column4);
        tableView.getColumns().add(column5);
        tableView.getColumns().add(column6);
        return tableView;
    }

    // Quote setup

    private void viewQuotes(Stage quoteStage) {
        TableView tableView = getQuoteTableView();
        VBox vbox = new VBox(tableView);
        Scene tradeStageScene = new Scene(vbox);
        quoteStage.setX(600);
        quoteStage.setY(500);
        quoteStage.setHeight(400);
        quoteStage.setWidth(500);
        quoteStage.setScene(tradeStageScene);

        MarketQuoteContainer quoteContainer = new MarketQuoteContainer();
        MarketQuoteUpdater quoteUpdater = new MarketQuoteUpdater(quoteContainer, true);

        AnimationTimer renderMarketDepth = getAnimationTimerForQuote(quoteStage, tableView, quoteContainer);
        renderMarketDepth.start();
        quoteUpdater.start();
        quoteStage.show();
    }

    private AnimationTimer getAnimationTimerForQuote(Stage quoteStage, TableView tableView, MarketQuoteContainer quoteContainer) {
        AnimationTimer animationTimer = new AnimationTimer() {
            @Override
            public void handle(long now) {
                if (quoteContainer.getLockObject().tryLock()) {
                    try {
                        quoteStage.setTitle("Quote for " + String.valueOf(quoteContainer.getQuote().getSymbol()));
                        Quote quote = quoteContainer.getQuote();
                        Quote lastQuote = quoteContainer.getLastQuote();
                        if (quote != null && quote.getExchange() != null && !lastQuote.equals(quote)) { // temporary workaround, has to be fixed at data side
                            tableView.getItems().add(new QuoteView(String.valueOf(quote.getTime()), String.valueOf(quote.getBidprice()),
                                    String.valueOf(quote.getBidsize()), String.valueOf(quote.getAsksize()), String.valueOf(quote.getAskprice()),
                                    String.valueOf(quote.getExchange()), String.valueOf(quote.getSymbol())));
                            quoteContainer.setLastQuote(quote);
                        }
                    } finally {
                        quoteContainer.getLockObject().unlock();
                    }
                }
            }
        };
        return animationTimer;
    }

    public static class QuoteView {
        private String quoteTime;
        private String bidprice;
        private String bidsize;
        private String asksize;
        private String askprice;
        private String exchange;
        private String symbol;

        public QuoteView(String quoteTime, String bidprice, String bidsize, String asksize, String askprice, String exchange, String symbol) {
            this.quoteTime = quoteTime;
            this.bidprice = bidprice;
            this.bidsize = bidsize;
            this.asksize = asksize;
            this.askprice = askprice;
            this.exchange = exchange;
            this.symbol = symbol;
        }

        public String getQuoteTime() {
            return quoteTime;
        }

        public void setQuoteTime(String quoteTime) {
            this.quoteTime = quoteTime;
        }

        public String getBidprice() {
            return bidprice;
        }

        public void setBidprice(String bidprice) {
            this.bidprice = bidprice;
        }

        public String getBidsize() {
            return bidsize;
        }

        public void setBidsize(String bidsize) {
            this.bidsize = bidsize;
        }

        public String getAsksize() {
            return asksize;
        }

        public void setAsksize(String asksize) {
            this.asksize = asksize;
        }

        public String getAskprice() {
            return askprice;
        }

        public void setAskprice(String askprice) {
            this.askprice = askprice;
        }

        public String getExchange() {
            return exchange;
        }

        public void setExchange(String exchange) {
            this.exchange = exchange;
        }

        public String getSymbol() {
            return symbol;
        }

        public void setSymbol(String symbol) {
            this.symbol = symbol;
        }
    }

    private TableView getQuoteTableView() {

        TableView tableView = new TableView();
        TableColumn<DepthData, String> column1 = new TableColumn<>("Time");
        column1.setCellValueFactory(new PropertyValueFactory<>("quoteTime"));

        TableColumn<DepthData, String> column2 = new TableColumn<>("Bidprice");
        column2.setCellValueFactory(new PropertyValueFactory<>("bidprice"));

        TableColumn<DepthData, String> column3 = new TableColumn<>("BidSize");
        column3.setCellValueFactory(new PropertyValueFactory<>("bidsize"));

        TableColumn<DepthData, String> column4 = new TableColumn<>("Asksize");
        column4.setCellValueFactory(new PropertyValueFactory<>("asksize"));

        TableColumn<DepthData, String> column5 = new TableColumn<>("Askprice");
        column5.setCellValueFactory(new PropertyValueFactory<>("askprice"));

        TableColumn<DepthData, String> column6 = new TableColumn<>("Exchange");
        column6.setCellValueFactory(new PropertyValueFactory<>("exchange"));

        TableColumn<DepthData, String> column7 = new TableColumn<>("Symbol");
        column7.setCellValueFactory(new PropertyValueFactory<>("symbol"));

        tableView.getColumns().add(column1);
        tableView.getColumns().add(column2);
        tableView.getColumns().add(column3);
        tableView.getColumns().add(column4);
        tableView.getColumns().add(column5);
        tableView.getColumns().add(column6);
        tableView.getColumns().add(column7);

        return tableView;
    }

    public static class MarketQuoteContainer {
        private Lock lockObject = new ReentrantLock();
        private Quote quote;
        private Quote lastQuote;

        public MarketQuoteContainer() {
            this.quote = new Quote();
            this.lastQuote = quote;
        }

        public Lock getLockObject() {
            return lockObject;
        }

        public Quote getQuote() {
            return quote;
        }

        public void setQuote(Quote quote) {
            this.quote = quote;
        }

        public Quote getLastQuote() {
            return lastQuote;
        }

        public void setLastQuote(Quote lastQuote) {
            this.lastQuote = lastQuote;
        }
    }

    public static class MarketQuoteUpdater extends Thread {
        private MarketQuoteContainer quoteContainer;
        private KafkaConsumer<String, String> kafkaConsumer;
        private EMSBroker emsBroker;
        private boolean kafka;

        public MarketQuoteUpdater(MarketQuoteContainer quoteContainer, boolean kafka) {
            this.quoteContainer = quoteContainer;
            this.kafka = kafka;
            try {
                if (!kafka) {
                    emsBroker = new EMSBroker("ashish-VirtualBox:7222", null, null);
                    emsBroker.createConsumer("exsim.nse.quotes", true);
                } else {
                    this.kafkaConsumer = new KafkaBroker("ashish-VirtualBox:9093,ashish-VirtualBox:9094,ashish-VirtualBox:9095").createConsumer(null);
                    this.kafkaConsumer.subscribe(Arrays.asList("exsim.nse.quotes"));
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        @Override
        public void run() {
            while (true) {
                quoteContainer.getLockObject().lock();
                try {
                    if (!kafka) {
                        Message msg = emsBroker.consumer().receive();
                        if (msg == null)
                            return;
                        if (msg instanceof TextMessage) {
                            TextMessage message = (TextMessage) msg;
                            byte[] decoded = Base64.getDecoder().decode(message.getText());
                            Quote quote = deSerealizeAvroHttpRequestJSON(decoded);
                            quoteContainer.setQuote(quote);
                        }
                    } else {
                        ConsumerRecords<String, String> records = kafkaConsumer.poll(java.time.Duration.ofMillis(10));
                        for (ConsumerRecord<String, String> record : records) {
                            String symbol = record.key();
                            String data = record.value();
                            byte[] decoded = Base64.getDecoder().decode(data);
                            {
                                Quote quote = deSerealizeAvroHttpRequestJSON(decoded);
                                quoteContainer.setQuote(quote);
                            }
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    quoteContainer.getLockObject().unlock();
                }
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                }
            }
        }

        public Quote deSerealizeAvroHttpRequestJSON(byte[] data) {
            DatumReader<Quote> reader
                    = new SpecificDatumReader<>(Quote.class);
            Decoder decoder = null;
            try {
                decoder = DecoderFactory.get().jsonDecoder(Quote.getClassSchema(), new String(data));
                return reader.read(null, decoder);
            } catch (IOException e) {
                //logger.error("Deserialization error:" + e.getMessage());
            }
            return null;
        }
    }

    /// trade setup
    private void viewTrades(Stage tradeStage) {
        TableView tableView = getTradeTableView();
        VBox vbox = new VBox(tableView);
        Scene tradeStageScene = new Scene(vbox);
        tradeStage.setX(100);
        tradeStage.setY(500);
        tradeStage.setHeight(400);
        tradeStage.setWidth(500);
        tradeStage.setScene(tradeStageScene);

        MarketTradeContainer tradeContainer = new MarketTradeContainer();
        MarketTradeUpdater tradeUpdater = new MarketTradeUpdater(tradeContainer, true);

        AnimationTimer renderMarketDepth = getAnimationTimerForTrade(tradeStage, tableView, tradeContainer);

        renderMarketDepth.start();
        tradeUpdater.start();
        tradeStage.show();
    }

    private AnimationTimer getAnimationTimerForTrade(Stage tradeStage, TableView tableView, MarketTradeContainer tradeContainer) {
        AnimationTimer animationTimer = new AnimationTimer() {
            @Override
            public void handle(long now) {
                if (tradeContainer.getLockObject().tryLock()) {
                    try {
                        tradeStage.setTitle("Trade for " + String.valueOf(tradeContainer.getTrade().getSymbol()));
                        Trade trade = tradeContainer.getTrade();
                        Trade lastTrade = tradeContainer.getLastTrade();
                        if (trade != null && trade.getExchange() != null && !trade.equals(lastTrade)) { // temporary workaround, has to be fixed at data side
                            tableView.getItems().add(new TradeView(String.valueOf(trade.getTime()), String.valueOf(trade.getSize()), String.valueOf(trade.getPrice()), String.valueOf(trade.getSymbol()), String.valueOf(trade.getExchange())));
                            tradeContainer.setLastTrade(trade);
                        }

                    } finally {
                        tradeContainer.getLockObject().unlock();
                    }
                }
            }
        };
        return animationTimer;
    }

    public static class TradeView {
        private String tradeTime;
        private String tradeQty;
        private String tradePrice;
        private String symbol;
        private String exchange;

        public TradeView(String tradeTime, String tradeQty, String tradePrice, String symbol, String exchange) {
            this.tradeTime = tradeTime;
            this.tradeQty = tradeQty;
            this.tradePrice = tradePrice;
            this.symbol = symbol;
            this.exchange = exchange;
        }

        public String getTradeTime() {
            return tradeTime;
        }

        public void setTradeTime(String tradeTime) {
            this.tradeTime = tradeTime;
        }

        public String getTradeQty() {
            return tradeQty;
        }

        public void setTradeQty(String tradeQty) {
            this.tradeQty = tradeQty;
        }

        public String getTradePrice() {
            return tradePrice;
        }

        public void setTradePrice(String tradePrice) {
            this.tradePrice = tradePrice;
        }

        public String getSymbol() {
            return symbol;
        }

        public void setSymbol(String symbol) {
            this.symbol = symbol;
        }

        public String getExchange() {
            return exchange;
        }

        public void setExchange(String exchange) {
            this.exchange = exchange;
        }
    }

    private TableView getTradeTableView() {
        TableView tableView = new TableView();

        TableColumn<DepthData, String> column1 = new TableColumn<>("Time");
        column1.setCellValueFactory(new PropertyValueFactory<>("tradeTime"));

        TableColumn<DepthData, String> column2 = new TableColumn<>("Quantity");
        column2.setCellValueFactory(new PropertyValueFactory<>("tradeQty"));

        TableColumn<DepthData, String> column3 = new TableColumn<>("Price");
        column3.setCellValueFactory(new PropertyValueFactory<>("tradePrice"));

        TableColumn<DepthData, String> column4 = new TableColumn<>("Symbol");
        column4.setCellValueFactory(new PropertyValueFactory<>("symbol"));

        TableColumn<DepthData, String> column5 = new TableColumn<>("Exchange");
        column5.setCellValueFactory(new PropertyValueFactory<>("exchange"));

        tableView.getColumns().add(column1);
        tableView.getColumns().add(column2);
        tableView.getColumns().add(column3);
        tableView.getColumns().add(column4);
        tableView.getColumns().add(column5);
        return tableView;
    }

    public static class MarketTradeContainer {
        private Lock lockObject = new ReentrantLock();
        private Trade trade;
        private Trade lastTrade;

        public MarketTradeContainer() {
            this.trade = new Trade();
            this.lastTrade = trade;
        }

        public Lock getLockObject() {
            return lockObject;
        }

        public Trade getTrade() {
            return trade;
        }

        public void setTrade(Trade trade) {
            this.trade = trade;
        }

        public Trade getLastTrade() {
            return lastTrade;
        }

        public void setLastTrade(Trade lastTrade) {
            this.lastTrade = lastTrade;
        }
    }

    public static class MarketTradeUpdater extends Thread {
        private MarketTradeContainer tradeContainer;
        private KafkaConsumer<String, String> kafkaConsumer;
        private EMSBroker emsBroker;
        private boolean kafka;

        public MarketTradeUpdater(MarketTradeContainer tradeContainer, boolean kafka) {
            this.tradeContainer = tradeContainer;
            this.kafka = kafka;
            try {
                if (!kafka) {
                    emsBroker = new EMSBroker("ashish-VirtualBox:7222", null, null);
                    emsBroker.createConsumer("exsim.nse.trades", true);
                } else {
                    this.kafkaConsumer = new KafkaBroker("ashish-VirtualBox:9093,ashish-VirtualBox:9094,ashish-VirtualBox:9095").createConsumer(null);
                    this.kafkaConsumer.subscribe(Arrays.asList("exsim.nse.trades"));
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        @Override
        public void run() {
            while (true) {
                tradeContainer.getLockObject().lock();
                try {
                    if (!kafka) {
                        Message msg = emsBroker.consumer().receive();
                        if (msg == null)
                            return;
                        if (msg instanceof TextMessage) {
                            TextMessage message = (TextMessage) msg;
                            byte[] decoded = Base64.getDecoder().decode(message.getText());
                            Trade trade = deSerealizeAvroHttpRequestJSON(decoded);
                            tradeContainer.setTrade(trade);
                        }
                    } else {
                        ConsumerRecords<String, String> records = kafkaConsumer.poll(java.time.Duration.ofMillis(10));
                        for (ConsumerRecord<String, String> record : records) {
                            String symbol = record.key();
                            String data = record.value();
                            byte[] decoded = Base64.getDecoder().decode(data);
                            {
                                Trade trade = deSerealizeAvroHttpRequestJSON(decoded);
                                tradeContainer.setTrade(trade);
                            }
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    tradeContainer.getLockObject().unlock();
                }
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                }
            }
        }

        public Trade deSerealizeAvroHttpRequestJSON(byte[] data) {
            DatumReader<Trade> reader
                    = new SpecificDatumReader<>(Trade.class);
            Decoder decoder = null;
            try {
                decoder = DecoderFactory.get().jsonDecoder(Trade.getClassSchema(), new String(data));
                return reader.read(null, decoder);
            } catch (IOException e) {
                //logger.error("Deserialization error:" + e.getMessage());
            }
            return null;
        }
    }

    private void addWindowResizeListener(Stage stage, Rectangle background) {
        ChangeListener<Number> stageSizeListener = ((observable, oldValue, newValue) -> {
            background.setHeight(stage.getHeight());
            background.setWidth(stage.getWidth());
        });
        stage.widthProperty().addListener(stageSizeListener);
        stage.heightProperty().addListener(stageSizeListener);
    }

}
