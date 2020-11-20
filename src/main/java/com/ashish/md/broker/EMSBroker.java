package com.ashish.md.broker;

import javax.jms.*;

public class EMSBroker implements ExceptionListener
{
    private String serverUrl = null;
    private String userName = null;
    private String password = null;
    private boolean useTopic = true;
    private int ackMode = Session.AUTO_ACKNOWLEDGE;
    private Session session = null;
    private Connection connection = null;
    private MessageConsumer msgConsumer = null;
    private MessageProducer msgProducer = null;
    private Destination consumerDestination = null;
    private Destination producerDestination = null;


    public EMSBroker(String serverUrl, String userName, String password) throws JMSException {
        this.serverUrl = serverUrl;
        this.userName = userName;
        this.password = password;
        this.open();
    }

    private void open() throws JMSException {
        ConnectionFactory factory = new com.tibco.tibjms.TibjmsConnectionFactory(serverUrl);
        /* create the connection */
        connection = factory.createConnection(userName, password);
        /* create the session */
        session = connection.createSession(ackMode);
        /* set the exception listener */
        connection.setExceptionListener(this);
    }

    //@Override
    public void retry() throws JMSException {
        open();
    }

    public void createProducer(final String topic, final boolean useTopic) throws JMSException {
        if (useTopic)
            producerDestination = session.createTopic(topic);
        else
            producerDestination = session.createQueue(topic);
        /* create the consumer */

        msgProducer = session.createProducer(null);
        connection.start();
    }


    public void createConsumer(String topic, boolean useTopic) throws JMSException {
        /* start the connection */
        if (useTopic)
            consumerDestination = session.createTopic(topic);
        else
            consumerDestination = session.createQueue(topic);
        /* create the consumer */
        msgConsumer = session.createConsumer(consumerDestination);
        connection.start();
    }

    public void send(TextMessage message) throws JMSException {
        msgProducer.send(producerDestination,message);
    }


    public void closeProducer() throws JMSException {
        msgProducer.close();
        session.close();
        connection.close();
    }

    public void closeConsumer() throws JMSException {
        msgConsumer.close();
        session.close();
        connection.close();
    }


    public int ackMode() {
        return this.ackMode;
    }


    public MessageConsumer consumer() {
        return msgConsumer;
    }


    public TextMessage createMessage() throws JMSException {
        return session.createTextMessage();
    }

    @Override
    public void onException(JMSException e) {
        System.err.println("CONNECTION EXCEPTION: " + e.getMessage());
    }

}
