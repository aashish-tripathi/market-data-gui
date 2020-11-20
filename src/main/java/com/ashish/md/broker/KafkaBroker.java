package com.ashish.md.broker;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;

import java.util.Enumeration;
import java.util.Properties;
import java.util.concurrent.ThreadLocalRandom;

public class KafkaBroker {

    private String kafkaServersUrl;
    protected Properties producerProperties;
    protected Properties consumerProperties;

    public  KafkaBroker(String kafkaServersUrl){
        this.kafkaServersUrl= kafkaServersUrl;
        initializeProducer();
        initializeConsumer();
    }

    private void initializeProducer() {
        // basic producer properties
        producerProperties = new Properties();
        producerProperties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaServersUrl);
        producerProperties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        producerProperties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
    }

    private void initializeConsumer() {
        // basic consumer properties
        consumerProperties = new Properties();
        consumerProperties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaServersUrl);
        consumerProperties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        consumerProperties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        consumerProperties.setProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,"earliest");
    }


    public KafkaProducer<String, String> createProducer(Properties optionalProperties){
        if(optionalProperties ==null)
            return  new KafkaProducer<String, String>(producerProperties); // return default kafka producer
        else{
            Enumeration<Object> keys = optionalProperties.keys();
            while(keys.hasMoreElements()){
                Object key = keys.nextElement();
                producerProperties.put(key, optionalProperties.get(key));
            }
        }
        return new KafkaProducer<String, String>(producerProperties);
    }

    public KafkaConsumer<String, String> createConsumer(Properties optionalProperties){
        ThreadLocalRandom local = ThreadLocalRandom.current();
        consumerProperties.setProperty(ConsumerConfig.GROUP_ID_CONFIG,"Order-Simulator-"+local.nextInt(10));
        if(optionalProperties ==null)
            return  new KafkaConsumer<String, String>(consumerProperties); // return default kafka consumer
        else{
            Enumeration<Object> keys = optionalProperties.keys();
            while(keys.hasMoreElements()){
                Object key = keys.nextElement();
                consumerProperties.put(key, optionalProperties.get(key));
            }
        }
        return new KafkaConsumer<String, String>(consumerProperties);
    }
}
