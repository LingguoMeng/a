package com.libzter.a;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.Enumeration;
import java.util.Map.Entry;
import java.util.Properties;

import javax.jms.BytesMessage;
import javax.jms.Connection;
import javax.jms.DeliveryMode;
import javax.jms.Destination;
import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.MessageConsumer;
import javax.jms.MessageProducer;
import javax.jms.Queue;
import javax.jms.QueueBrowser;
import javax.jms.Session;
import javax.jms.TextMessage;

import org.apache.activemq.ActiveMQConnectionFactory;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.OptionBuilder;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A - An ActiveMQ/JMS testing and admin tool
 */
public class A {
	private static final Logger logger = LoggerFactory.getLogger(A.class);
	protected ActiveMQConnectionFactory cf;
	protected Connection conn;
	protected Session sess;
	protected CommandLine cmdLine;
	
	public static void main(String[] args) throws ParseException, InterruptedException{
		A a = new A();
		a.run(args);
	}
	
	public void run(String[] args) throws InterruptedException{
		Options opts = new Options();
		opts.addOption("b", "broker", true, "URL to broker. defaults to: tcp://localhost:61616");
		opts.addOption("g","get", false, "Get a message from destination");
		opts.addOption("p","put", true, "Put a message. Specify data. if starts with @, a file is assumed and loaded");
		opts.addOption("t","type",true, "Message type to put, [bytes, text] - defaults to text");
		opts.addOption("e","encoding",true,"Encoding of input file data. Default UTF-8");
		opts.addOption("n","non-persistent",false,"Set message to non persistent.");
		opts.addOption("r","reply-to",true,"Set reply to destination, i.e. queue:reply");
		opts.addOption("o","output",true,"file to write payload to. If multiple messages, a -1.<ext> will be added to the file. BytesMessage will be written as-is, TextMessage will be written in UTF-8");
		opts.addOption("c","count",true,"A number of messages to browse,get or put (put will put the same message <count> times). 0 means all messages.");
		Option property = OptionBuilder.withArgName("property=value" )
                .hasArgs(2)
                .withValueSeparator()
                .withDescription( "use value for given property. Can be used several times." )
                .create( "H" );
		
		opts.addOption(property);
		
		if( args.length == 0){
			HelpFormatter helpFormatter = new HelpFormatter();
			helpFormatter.printHelp("java -jar a.jar", opts, true);
			System.exit(0);
		}
		
		CommandLineParser cmdParser = new org.apache.commons.cli.PosixParser();
		
		try {
			cmdLine = cmdParser.parse(opts, args);
			connect(cmdLine.getOptionValue("b", "tcp://localhost:61616"),
					cmdLine.getOptionValue("user"),
					cmdLine.getOptionValue("pass"));
			
			 long startTime = System.currentTimeMillis();
			
			if( cmdLine.hasOption("g")){
				executeGet(cmdLine);
			}else if(cmdLine.hasOption("p")){
				executePut(cmdLine);
			}else{
				executeBrowse(cmdLine);
			}

		  long stopTime = System.currentTimeMillis();
		  long elapsedTime = stopTime - startTime;
		  System.out.println("Operation completed in " + elapsedTime + "ms (excluding connect)");
		} catch (ParseException pe) {
			pe.printStackTrace();
			return;
		} catch (JMSException je){
			je.printStackTrace();
			return;
		} catch (Exception e){
			e.printStackTrace();
			return;
		} finally{
			try {
				sess.close();
				conn.close();
			} catch (JMSException e2) {
				e2.printStackTrace();
			}
		}
		logger.debug("Active threads " + Thread.activeCount());
		logger.debug("At the end of the road");
	}

	private void connect(String optionValue,String user, String password) throws JMSException {
		cf = new ActiveMQConnectionFactory();
		if( user != null && password != null){
			conn = (Connection) cf.createConnection(user, password);
		}else{
			conn = cf.createConnection();
		}
		
		sess = conn.createSession(false, Session.AUTO_ACKNOWLEDGE);
		conn.start();
	}

	private void executeGet(CommandLine cmdLine) throws JMSException, UnsupportedEncodingException, IOException {
		Queue q = sess.createQueue(cmdLine.getArgs()[0]);
		MessageConsumer mq = sess.createConsumer(q);
		int count = Integer.parseInt(cmdLine.getOptionValue("c","1"));
		int i = 0;
		while(i < count || i == 0 ){
			Message msg = mq.receive(100L);
			if( msg == null){
				System.out.println("No message on queue");
				break;
			}else{
				outputMessage(msg);
				++i;
			}
		}
	}

	/**
	 * Put a message to a queue
	 * @param  cmdLine      [description]
	 * @throws IOException  [description]
	 * @throws JMSException [description]
	 */
	private void executePut(CommandLine cmdLine) throws IOException, JMSException {
		// Check if we have properties to put
		Properties props = cmdLine.getOptionProperties("H");
		String type = cmdLine.getOptionValue("t","text");
		String encoding = cmdLine.getOptionValue("e","UTF-8");
		Message outMsg = null;
		// figure out input data
		String data = cmdLine.getOptionValue("p");
		if( data.startsWith("@")){
			// Load file.
			byte[] bytes = FileUtils.readFileToByteArray(new File(data.substring(1)));
			if( type.equals("text")){
				TextMessage textMsg = sess.createTextMessage(new String(bytes,encoding));
				outMsg = textMsg;
			}else{
				BytesMessage bytesMsg = sess.createBytesMessage();
				bytesMsg.writeBytes(bytes);
			}
		}else{
			TextMessage textMsg = sess.createTextMessage(data);
			outMsg = textMsg;
		}
		
		MessageProducer mp = sess.createProducer(createDestination(cmdLine.getArgs()[0]));
		if( cmdLine.hasOption("n")){;
			mp.setDeliveryMode(DeliveryMode.NON_PERSISTENT);
		}

		// enrich headers.	
		for(Entry<Object,Object> p : props.entrySet()){
			outMsg.setObjectProperty((String)p.getKey(), p.getValue());
		}
		
		if( cmdLine.hasOption("r")){
			outMsg.setJMSReplyTo(createDestination(cmdLine.getOptionValue("r")));
		}
		// send multiple messages?
		if( cmdLine.hasOption("c")){
			int count = Integer.parseInt(cmdLine.getOptionValue("c"));
			for(int i=0;i<count;i++){
				mp.send(outMsg);
			}
			System.out.println("" + count + " messages sent");
		}else{
			mp.send(outMsg);
			System.out.println("Message sent");
		}
	}

	private Destination createDestination(String name) throws JMSException {
		// support queue:// as well.
		name = name.replace("/","");
		if( name.toLowerCase().startsWith("queue:")){
			return sess.createQueue(name.substring("queue:".length()));
		}else if( name.toLowerCase().startsWith("topic:")){
			return sess.createTopic(name.substring("topic:".length()));
		}else{
			return sess.createQueue(name);
		}
	}

	private void executeBrowse(CommandLine cmdLine) throws JMSException, UnsupportedEncodingException, IOException {
		Queue q = sess.createQueue(cmdLine.getArgs()[0]);
		QueueBrowser qb = sess.createBrowser(q);
		@SuppressWarnings("rawtypes")
		Enumeration en = qb.getEnumeration();
		int count = Integer.parseInt(cmdLine.getOptionValue("c","0"));
		int i = 0;
		while(en.hasMoreElements() && (i < count || i == 0 )){
			Object obj = en.nextElement();
			Message msg = (Message)obj;
			outputMessage(msg);
			++i;
		}
	}

	private void outputMessage(Message msg) throws JMSException, UnsupportedEncodingException, IOException {
		output("-----------------");
		outputProperties(msg);
		// Output to file?
		FileOutputStream fos = null;
		File file = null;
		if( cmdLine.hasOption("o")){
			file = getNextFilename(cmdLine.getOptionValue("o","amsg"),0);
			if( file != null ) {
				fos = new FileOutputStream(file);
			}
		}
		
		if( msg instanceof TextMessage){
			TextMessage txtMsg = (TextMessage)msg;
			if( fos != null){
				fos.write(txtMsg.getText().getBytes(cmdLine.getOptionValue("e","UTF-8")));
				fos.close();
				output("Payload written to file " + file.getAbsolutePath());
			}else{
				output("Payload:");
				output(txtMsg.getText());
			}
		}else if( msg instanceof BytesMessage){
			BytesMessage bmsg = (BytesMessage)msg;
			byte[] bytes = new byte[(int) bmsg.getBodyLength()];
			bmsg.readBytes(bytes);
			if( fos != null ){
				fos.write(bytes);
				fos.close();
				output("Payload written to file " + file.getAbsolutePath());
			}else{
				output("Hex Payload:");
				output(bytesToHex(bytes));
			}
		}else{
			System.out.println("Unsupported message type: " + msg.getClass().getName());
		}
	}

	private File getNextFilename(String suggestedFilename, int i) {
		String filename = suggestedFilename;
		if( i > 0 ){
			int idx = filename.lastIndexOf('.');
			if( idx == -1 ){
				filename = suggestedFilename + "-" + i;
			}else{
				// take care of the extension.
				filename = filename.substring(0,idx) + "-" + i + filename.substring(idx);
			}
		}
		File f = new File(filename);
		if( f.exists() ){
			return getNextFilename(suggestedFilename, ++i);
		}else{
			return f;
		}
	}

	private void outputProperties(Message msg) throws JMSException {
		output("Message Properties");
		@SuppressWarnings("unchecked")
		Enumeration<String> en = msg.getPropertyNames();
		while(en.hasMoreElements()){
			String name = en.nextElement();
			Object property = msg.getObjectProperty(name);
			output(name + ": " + property.toString());
		}
	}
	
	private void output(String... args){
		for(String arg : args){
			System.out.println(arg);
		}
	}
	
	final protected static char[] hexArray = "0123456789ABCDEF".toCharArray();
	public String bytesToHex(byte[] bytes) {
	    char[] hexChars = new char[bytes.length * 2];
	    for ( int j = 0; j < bytes.length; j++ ) {
	        int v = bytes[j] & 0xFF;
	        hexChars[j * 2] = hexArray[v >>> 4];
	        hexChars[j * 2 + 1] = hexArray[v & 0x0F];
	    }
	    return new String(hexChars);
	}
}
