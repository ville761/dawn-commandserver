package org.dawnsci.commandserver.core.producer;

import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.jms.Connection;
import javax.jms.ConnectionFactory;
import javax.jms.Message;
import javax.jms.MessageConsumer;
import javax.jms.MessageProducer;
import javax.jms.Queue;
import javax.jms.QueueBrowser;
import javax.jms.QueueConnection;
import javax.jms.QueueConnectionFactory;
import javax.jms.QueueSession;
import javax.jms.Session;
import javax.jms.TextMessage;

import org.dawnsci.commandserver.core.ConnectionFactoryFacade;
import org.dawnsci.commandserver.core.beans.Status;
import org.dawnsci.commandserver.core.beans.StatusBean;
import org.dawnsci.commandserver.core.consumer.RemoteSubmission;
import org.dawnsci.commandserver.core.process.ProgressableProcess;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * This consumer monitors a queue and starts runs based
 * on what is submitted.
 * 
 * Please extend this consumer to create it and call the start method.
 * 
 * @author fcp94556
 *
 */
public abstract class SubmissionConsumer {
	

	private String uri, submitQName, statusTName, statusQName;
	
	public SubmissionConsumer(String uri, 
			                  String submitQName,
			                  String statusTName,
			                  String statusQName) {
		this.uri         = uri;
		this.submitQName = submitQName;
		this.statusTName = statusTName;
		this.statusQName = statusQName;
	}
	
	/**
	 * Starts the consumer and does not return.
	 * @throws Exception
	 */
	public void start() throws Exception {
		cleanStatusQueue(uri, statusQName);
		
		// This is the blocker
		monitorSubmissionQueue(uri, submitQName, statusTName, statusQName);
	}

	/**
	 * Implement to return the actual bean class in the queue
	 * @return
	 */
	protected abstract Class<? extends StatusBean> getBeanClass();
	
	/**
	 * Implement to create the required command server process.
	 * 
	 * @param uri
	 * @param statusTName
	 * @param statusQName
	 * @param bean
	 * @return
	 */
	protected abstract ProgressableProcess createProcess(String uri, String statusTName, String statusQName, StatusBean bean);


	/**
	 * WARNING - starts infinite loop - you have to kill 
	 * @param uri
	 * @param submitQName
	 * @param statusTName
	 * @param statusQName
	 * @throws Exception
	 */
	private void monitorSubmissionQueue(String uri, 
										String submitQName,
										String statusTName, 
										String statusQName) throws Exception {

		ConnectionFactory connectionFactory = ConnectionFactoryFacade.createConnectionFactory(uri);
		Connection    connection = connectionFactory.createConnection();
		Session   session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
		Queue queue = session.createQueue(submitQName);

		final MessageConsumer consumer = session.createConsumer(queue);
		connection.start();
		
		System.out.println("Starting consumer for submissions to queue "+submitQName);
        while (true) { // You have to kill it to stop it!
            
        	try {
        		
        		// Consumes messages from the queue.
	        	Message m = consumer.receive(1000);
	            if (m!=null) {
	            	TextMessage t = (TextMessage)m;
	            	ObjectMapper mapper = new ObjectMapper();
	            	
	            	final StatusBean bean = mapper.readValue(t.getText(), getBeanClass());
	            	
                    if (bean!=null) { // We add this to the status list so that it can be rendered in the UI
                    	
                    	// Now we put the bean in the status queue and we 
                    	// start the process
                    	RemoteSubmission factory = new RemoteSubmission(uri);
                    	factory.setLifeTime(t.getJMSExpiration());
                    	factory.setPriority(t.getJMSPriority());
                    	factory.setTimestamp(t.getJMSTimestamp());
                    	factory.setQueueName(statusQName); // Move the message over to a status queue.
                    	
                    	factory.submit(bean, false);
                    	
                    	final ProgressableProcess process = createProcess(uri, statusTName, statusQName, bean); // TODO Xia2 anyone?
                    	process.start();
                    	
                    	System.out.println("Started job "+bean.getName()+" messageid("+t.getJMSMessageID()+")");
                    }
	            }
        	} catch (Throwable ne) {
        		// Really basic error reporting, they have to pipe to file.
        		ne.printStackTrace();
        	}
		}
		
	}
	
	/**
	 * 
	 * @param bean
	 * @throws Exception 
	 */
	private void cleanStatusQueue(String uri, String statusQName) throws Exception {
		
		QueueConnection qCon = null;
		
		try {
	 	    QueueConnectionFactory connectionFactory = ConnectionFactoryFacade.createConnectionFactory(uri);
			qCon  = connectionFactory.createQueueConnection(); 
			QueueSession    qSes  = qCon.createQueueSession(false, Session.AUTO_ACKNOWLEDGE);
			Queue queue   = qSes.createQueue(statusQName);
			qCon.start();
			
		    QueueBrowser qb = qSes.createBrowser(queue);
		    
		    @SuppressWarnings("rawtypes")
			Enumeration  e  = qb.getEnumeration();
		    
			ObjectMapper mapper = new ObjectMapper();
			
			Map<String, StatusBean> failIds = new LinkedHashMap<String, StatusBean>(7);
			List<String>          removeIds = new ArrayList<String>(7);
	        while(e.hasMoreElements()) {
		    	Message m = (Message)e.nextElement();
		    	if (m==null) continue;
	        	if (m instanceof TextMessage) {
	            	TextMessage t = (TextMessage)m;
	              	
	            	try {
		            	@SuppressWarnings("unchecked")
						final StatusBean qbean = mapper.readValue(t.getText(), getBeanClass());
		            	if (qbean==null)               continue;
		            	if (qbean.getStatus()==null)   continue;
		            	if (!qbean.getStatus().isStarted()) {
		            		failIds.put(t.getJMSMessageID(), qbean);
		            	}
	            	} catch (Exception ne) {
	            		System.out.println("Message "+t.getText()+" is not legal and will be removed.");
	            		removeIds.add(t.getJMSMessageID());
	            	}
	        	}
		    }
	        
	        // We fail the non-started jobs now - otherwise we could
	        // actually start them late. TODO check this
        	final List<String> ids = new ArrayList<String>();
        	ids.addAll(failIds.keySet());
        	ids.addAll(removeIds);
        	
	        if (ids.size()>0) {
	        	
	        	for (String jMSMessageID : ids) {
		        	MessageConsumer consumer = qSes.createConsumer(queue, "JMSMessageID = '"+jMSMessageID+"'");
		        	Message m = consumer.receive(1000);
		        	if (removeIds.contains(jMSMessageID)) continue; // We are done
		        	
		        	if (m!=null && m instanceof TextMessage) {
		        		MessageProducer producer = qSes.createProducer(queue);
		        		final StatusBean    bean = failIds.get(jMSMessageID);
		        		bean.setStatus(Status.FAILED);
		        		producer.send(qSes.createTextMessage(mapper.writeValueAsString(bean)));
		        		
                    	System.out.println("Failed job "+bean.getName()+" messageid("+jMSMessageID+")");

		        	}
				}
	        }
		} finally {
			if (qCon!=null) qCon.close();
		}
		
	}

}
