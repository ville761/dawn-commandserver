/*
 * Copyright (c) 2012 Diamond Light Source Ltd.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.dawnsci.commandserver.ui.view;

import java.io.File;
import java.net.URI;
import java.text.DateFormat;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Date;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeSet;

import javax.jms.Connection;
import javax.jms.ConnectionFactory;
import javax.jms.Message;
import javax.jms.MessageConsumer;
import javax.jms.MessageListener;
import javax.jms.Queue;
import javax.jms.QueueBrowser;
import javax.jms.QueueConnection;
import javax.jms.QueueConnectionFactory;
import javax.jms.QueueSession;
import javax.jms.Session;
import javax.jms.TextMessage;
import javax.jms.Topic;

import org.dawb.common.ui.util.EclipseUtils;
import org.dawb.common.ui.util.GridUtils;
import org.dawb.common.util.io.PropUtils;
import org.dawnsci.commandserver.core.ConnectionFactoryFacade;
import org.dawnsci.commandserver.core.beans.AdministratorMessage;
import org.dawnsci.commandserver.core.beans.StatusBean;
import org.dawnsci.commandserver.core.consumer.Constants;
import org.dawnsci.commandserver.core.consumer.RemoteSubmission;
import org.dawnsci.commandserver.core.util.CmdUtils;
import org.dawnsci.commandserver.core.util.JSONUtils;
import org.dawnsci.commandserver.ui.Activator;
import org.dawnsci.commandserver.ui.dialog.PropertiesDialog;
import org.dawnsci.commandserver.ui.preference.CommandConstants;
import org.eclipse.core.runtime.IConfigurationElement;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.IAction;
import org.eclipse.jface.action.IContributionManager;
import org.eclipse.jface.action.MenuManager;
import org.eclipse.jface.action.Separator;
import org.eclipse.jface.dialogs.ErrorDialog;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.viewers.ColumnLabelProvider;
import org.eclipse.jface.viewers.IContentProvider;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.ISelectionChangedListener;
import org.eclipse.jface.viewers.IStructuredContentProvider;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.SelectionChangedEvent;
import org.eclipse.jface.viewers.TableViewer;
import org.eclipse.jface.viewers.TableViewerColumn;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.MouseAdapter;
import org.eclipse.swt.events.MouseEvent;
import org.eclipse.swt.events.MouseMoveListener;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.ui.IEditorDescriptor;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.part.ViewPart;
import org.eclipse.ui.preferences.ScopedPreferenceStore;
import org.osgi.framework.Bundle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * A view for which the secondary id MUST be set and provides the queueName
 * and optionally the queue view name if a custom one is required. Syntax of
 * these parameters in the secondary id are key1=value1;key2=value2...
 * 
 * The essential keys are: beanBundleName, beanClassName, queueName, topicName, submissionQueueName
 * You can use createId(...) to generate a legal id from them.
 * 
 * The optional keys are: partName, 
 *                        uri (default CommandConstants.JMS_URI),
 *                        userName (default is user.name system property)
 * 
 * Example id for this view would be:
 * org.dawnsci.commandserver.ui.queueView:beanClassName=org.dawnsci.commandserver.mx.beans.ProjectBean;beanBundleName=org.dawnsci.commandserver.mx
 * 
 * You can optionally extend this class to provide a table which is displayed for your
 * queue of custom objects. For instance for a queue showing xia2 reruns, the 
 * extra columns for this could be defined. However by default the 
 * 
 * @author Matthew Gerring
 *
 */
public class StatusQueueView extends ViewPart {
	
	public static final String ID = "org.dawnsci.commandserver.ui.queueView";
	
	private static final Logger logger = LoggerFactory.getLogger(StatusQueueView.class);
	
	// UI
	private TableViewer                       viewer;
	
	// Data
	private Properties                        idProperties;
	private Map<String, StatusBean>           queue;
	private boolean                           showEntireQueue = false;

	private Connection topicConnection;

	private Action kill;

	@Override
	public void createPartControl(Composite content) {
		
		content.setLayout(new GridLayout(1, false));
		GridUtils.removeMargins(content);

		this.viewer   = new TableViewer(content, SWT.FULL_SELECTION | SWT.MULTI | SWT.V_SCROLL | SWT.H_SCROLL);
		viewer.setUseHashlookup(true);
		viewer.getTable().setHeaderVisible(true);
		viewer.getControl().setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
		
		
		createColumns();
		viewer.setContentProvider(createContentProvider());
		
        try {
    		updateQueue(getUri());
    		
    		String name = getSecondaryIdAttribute("partName");
            if (name!=null) setPartName(name);
    		
            createActions();

            createTopicListener(getUri());
            
		} catch (Exception e) {
			logger.error("Cannot listen to topic of command server!", e);
		}
        
		getViewSite().setSelectionProvider(viewer);
		viewer.addSelectionChangedListener(new ISelectionChangedListener() {	
			@Override
			public void selectionChanged(SelectionChangedEvent event) {
				final StatusBean bean = getSelection();
				boolean enabled = true;
				if (bean==null) enabled = false;
				if (bean!=null) enabled = !bean.getStatus().isFinal();
				kill.setEnabled(enabled);
			}
		});

	}
	
	/**
	 * Listens to a topic
	 */
	private void createTopicListener(final URI uri) throws Exception {
		
		// Use job because connection might timeout.
		final Job topicJob = new Job("Create topic listener") {

			@Override
			protected IStatus run(IProgressMonitor monitor) {
				try {
					ConnectionFactory connectionFactory = ConnectionFactoryFacade.createConnectionFactory(uri);
			        StatusQueueView.this.topicConnection = connectionFactory.createConnection();
			        topicConnection.start();
	
			        Session session = topicConnection.createSession(false, Session.AUTO_ACKNOWLEDGE);
	
			        Topic           topic    = session.createTopic(getTopicName());
			        MessageConsumer consumer = session.createConsumer(topic);
	
			        final Class        clazz  = getBeanClass();
			        final ObjectMapper mapper = new ObjectMapper();
			        
			        MessageListener listener = new MessageListener() {
			            public void onMessage(Message message) {		            	
			                try {
			                    if (message instanceof TextMessage) {
			                        TextMessage t = (TextMessage) message;
			        				final StatusBean bean = mapper.readValue(t.getText(), clazz);
			                        mergeBean(bean);
			                    }
			                } catch (Exception e) {
			                    logger.error("Updating changed bean from topic", e);
			                }
			            }
			        };
			        consumer.setMessageListener(listener);
			        

			        // Create a listener for administrator broadcast messages.
			        topic    = session.createTopic(Constants.ADMIN_MESSAGE_TOPIC);
			        consumer = session.createConsumer(topic);
			        listener = new MessageListener() {
			            public void onMessage(Message message) {		            	
			                try {
			                    if (message instanceof TextMessage) {
			    			        // AdministratorMessage shows a message to the user.
			    			        final Class        msgClass  = AdministratorMessage.class;

			    			        TextMessage t = (TextMessage) message;
			        				final AdministratorMessage bean = mapper.readValue(t.getText(), msgClass);
			                        
			        				getSite().getShell().getDisplay().syncExec(new Runnable() {
			        					public void run() {
			                                   MessageDialog.openError(getViewSite().getShell(), 
			                                		                   bean.getTitle(), 
			                                		                   bean.getMessage());
			                                   
			                                   viewer.refresh();
			        					}
			        				});
 			                    }
			                } catch (Exception e) {
			                    // Not a big deal if they do not get admin messages.
			                }
			            }
			        };
			        consumer.setMessageListener(listener);

			        return Status.OK_STATUS;
			        
				} catch (Exception ne) {
					logger.error("Cannot listen to topic changes because command server is not there", ne);
			        return Status.CANCEL_STATUS;
				}
			}
			
			
		};
		
		topicJob.setPriority(Job.INTERACTIVE);
		topicJob.setSystem(true);
		topicJob.setUser(false);
		topicJob.schedule();
	}
	
	public void dispose() {
		super.dispose();
		try {
			if (topicConnection!=null) topicConnection.close();
		} catch (Exception ne) {
			logger.warn("Problem stopping topic listening for "+getTopicName(), ne);
		}
	}

	/**
	 * Updates the bean if it is found in the list, otherwise
	 * refreshes the whole list because a bean we are not reporting
	 * has been(bean?) encountered.
	 * 
	 * @param bean
	 */
	protected void mergeBean(final StatusBean bean) throws Exception {
		
		getSite().getShell().getDisplay().asyncExec(new Runnable() {
			public void run(){
				if (queue.containsKey(bean.getUniqueId())) {
					queue.get(bean.getUniqueId()).merge(bean);
					viewer.refresh();
				} else {
					reconnect();
				}
			}
		});
	}

	private void createActions() {
		
		final IContributionManager toolMan = getViewSite().getActionBars().getToolBarManager();
		final MenuManager          menuMan = new MenuManager();
	
		final Action openResults = new Action("Open results for selected run", Activator.getDefault().getImageDescriptor("icons/results.png")) {
			public void run() {
				openResults(getSelection());
			}
		};
		
		toolMan.add(openResults);
		menuMan.add(openResults);
		toolMan.add(new Separator());
		menuMan.add(new Separator());
		
		
		this.kill = new Action("Terminate job", Activator.getDefault().getImageDescriptor("icons/terminate.png")) {
			public void run() {
				
				final StatusBean bean = getSelection();
				if (bean==null) return;
				
				if (bean.getStatus().isFinal()) {
					MessageDialog.openInformation(getViewSite().getShell(), "Run '"+bean.getName()+"' inactive", "Run '"+bean.getName()+"' is inactive and cannot be terminated.");
					return;
				}
				try {
					
					final DateFormat format = DateFormat.getDateTimeInstance();
					boolean ok = MessageDialog.openQuestion(getViewSite().getShell(), "Confirm terminate "+bean.getName(), 
							  "Are you sure you want to terminate "+bean.getName()+" submitted on "+format.format(new Date(bean.getSubmissionTime()))+"?");
					
					if (!ok) return;
					
					bean.setStatus(org.dawnsci.commandserver.core.beans.Status.REQUEST_TERMINATE);
					bean.setMessage("Requesting a termination of "+bean.getName());
					JSONUtils.sendTopic(bean, getTopicName(), getUri());
					
				} catch (Exception e) {
					ErrorDialog.openError(getViewSite().getShell(), "Cannot terminate "+bean.getName(), "Cannot terminate "+bean.getName()+"\n\nPlease contact your support representative.",
							new Status(IStatus.ERROR, "org.dawnsci.commandserver.ui", e.getMessage()));
				}
			}
		};
		toolMan.add(kill);
		menuMan.add(kill);
		
		final Action rerun = new Action("Rerun", Activator.getDefault().getImageDescriptor("icons/rerun.png")) {
			public void run() {
				rerunSelection();
			}
		};
		toolMan.add(rerun);
		menuMan.add(rerun);

		toolMan.add(new Separator());
		menuMan.add(new Separator());
		
		final Action showAll = new Action("Show all reruns", IAction.AS_CHECK_BOX) {
			public void run() {
				showEntireQueue = isChecked();
				viewer.refresh();
			}
		};
		showAll.setImageDescriptor(Activator.getDefault().getImageDescriptor("icons/spectacle-lorgnette.png"));
		
		toolMan.add(showAll);
		menuMan.add(showAll);
		
		toolMan.add(new Separator());
		menuMan.add(new Separator());

		
		final Action refresh = new Action("Refresh", Activator.getDefault().getImageDescriptor("icons/arrow-circle-double-135.png")) {
			public void run() {
				reconnect();
			}
		};
		
		toolMan.add(refresh);
		menuMan.add(refresh);

		final Action configure = new Action("Configure...", Activator.getDefault().getImageDescriptor("icons/document--pencil.png")) {
			public void run() {
				PropertiesDialog dialog = new PropertiesDialog(getSite().getShell(), idProperties);
				
				int ok = dialog.open();
				if (ok == PropertiesDialog.OK) {
					idProperties.clear();
					idProperties.putAll(dialog.getProps());
					reconnect();
				}
			}
		};
		
		toolMan.add(configure);
		menuMan.add(configure);
		
		viewer.getControl().setMenu(menuMan.createContextMenu(viewer.getControl()));
	}

	protected void rerunSelection() {
		
		final StatusBean bean = getSelection();
		if (bean==null) return;

		try {
			final IConfigurationElement[] c = Platform.getExtensionRegistry().getConfigurationElementsFor("org.dawnsci.commandserver.ui.rerunHandler");
			if (c!=null) {
				for (IConfigurationElement i : c) {
					final IRerunHandler handler = (IRerunHandler)i.createExecutableExtension("class");
					if (handler.isHandled(bean)) {
						boolean ok = handler.run(bean);
						if (ok) return;
					}
				}
			}
		} catch (Exception ne) {
			ne.printStackTrace();
			ErrorDialog.openError(getSite().getShell(), "Internal Error", "Cannot rerun "+bean.getRunDirectory()+" normally.\n\nPlease contact your support representative.", 
					new Status(IStatus.ERROR, Activator.PLUGIN_ID, ne.getMessage()));
			return;
		}
    
		// If we have not already handled this rerun, it is possible to call a generic one.
		rerun(bean);
	}

	private void rerun(StatusBean bean) {
		
		try {
			
			final DateFormat format = DateFormat.getDateTimeInstance();
			boolean ok = MessageDialog.openQuestion(getViewSite().getShell(), "Confirm resubmission "+bean.getName(), 
					  "Are you sure you want to rerun "+bean.getName()+" submitted on "+format.format(new Date(bean.getSubmissionTime()))+"?");
			
			if (!ok) return;
			
			final StatusBean copy = bean.getClass().newInstance();
			copy.merge(bean);
			copy.setMessage("Rerun of "+bean.getName());
			
			IPreferenceStore store = new ScopedPreferenceStore(InstanceScope.INSTANCE, "org.dawnsci.commandserver.ui");
			final URI    uri       = new URI(store.getString("org.dawnsci.commandserver.URI"));
			
			final RemoteSubmission factory = new RemoteSubmission(uri);
			factory.setQueueName(getSubmissionQueueName());
			
			factory.submit(copy, true);
			
			reconnect();

		} catch (Exception e) {
			ErrorDialog.openError(getViewSite().getShell(), "Cannot rerun "+bean.getName(), "Cannot rerun "+bean.getName()+"\n\nPlease contact your support representative.",
					new Status(IStatus.ERROR, "org.dawnsci.commandserver.ui", e.getMessage()));
		}
		
	}

	protected void reconnect() {
		try {
			updateQueue(getUri());
		} catch (Exception e) {
			logger.error("Cannot resolve uri for activemq server of "+getSecondaryIdAttribute("uri"));
		}
	}
	
	private IContentProvider createContentProvider() {
		return new IStructuredContentProvider() {
			
			@Override
			public void inputChanged(Viewer viewer, Object oldInput, Object newInput) {
				queue = (Map<String, StatusBean>)newInput;
			}
			
			@Override
			public void dispose() {
				if (queue!=null) queue.clear();
			}
			
			@Override
			public Object[] getElements(Object inputElement) {
				if (queue==null) return new StatusBean[]{StatusBean.EMPTY};
				final List<StatusBean> retained = new ArrayList<StatusBean>(queue.values());
				
				// This preference is not secure people could hack DAWN to do this.
				if (!Boolean.getBoolean("org.dawnsci.commandserver.ui.view.showWholeQueue")) {
					// Old fashioned loop. In Java8 we will use a predicate...
					final String userName = getUserName();
					for (Iterator it = retained.iterator(); it.hasNext();) {
						StatusBean statusBean = (StatusBean) it.next();
						if (statusBean.getUserName()==null) continue;
						if (!showEntireQueue) {
							if (!userName.equals(statusBean.getUserName())) it.remove();
						}
					}
					// This form of filtering is not at all secure because we
					// give the full list of the queue to the clients.
				}
				return retained.toArray(new StatusBean[retained.size()]);
			}
		};
	}
	
	protected StatusBean getSelection() {
		final ISelection sel = viewer.getSelection();
		if (sel instanceof IStructuredSelection) {
			IStructuredSelection ss = (IStructuredSelection)sel;
			if (ss.size()>0) return (StatusBean)ss.getFirstElement();
		}
		return null;
	}

	/**
	 * Read Queue and return in submission order.
	 * @param uri
	 * @return
	 * @throws Exception
	 */
	@SuppressWarnings("unchecked")
	protected synchronized void updateQueue(final URI uri) {
		

		final Job queueJob = new Job("Connect and read queue") {

			@Override
			protected IStatus run(IProgressMonitor monitor) {
				try {
					monitor.beginTask("Connect to command server", 10);
					monitor.worked(1);
					
					Collection<StatusBean> runningList = getStatusBeans(uri, getQueueName(), monitor);
					monitor.worked(1);
			        
					Collection<StatusBean> submittedList = getStatusBeans(uri, getSubmissionQueueName(), monitor);
					monitor.worked(1);

					// We reverse the queue because it comes out date ascending and we
			        // want newest submissions first.
					final Map<String,StatusBean> ret = new LinkedHashMap<String,StatusBean>();
					for (StatusBean bean : submittedList) {
			        	ret.put(bean.getUniqueId(), bean);
					}
					monitor.worked(1);
			        for (StatusBean bean : runningList) {
			        	ret.put(bean.getUniqueId(), bean);
					}
					monitor.worked(1);
			        
			        getSite().getShell().getDisplay().syncExec(new Runnable() {
			        	public void run() {
			        		viewer.setInput(ret);
			        		viewer.refresh();
			        	}
			        });
			        monitor.done();
			        
			        return Status.OK_STATUS;
			        
				} catch (final Exception e) {
					
			        monitor.done();
			        logger.error("Updating changed bean from topic", e);
			        getSite().getShell().getDisplay().syncExec(new Runnable() {
			        	public void run() {
							ErrorDialog.openError(getViewSite().getShell(), "Cannot connect to queue", "The command server is unavailable.\n\nPlease contact your support representative.", 
						              new Status(IStatus.ERROR, Activator.PLUGIN_ID, e.getMessage()));
			        	}
			        });
			        return Status.CANCEL_STATUS;

				}			
			}
			
		};
		queueJob.setPriority(Job.INTERACTIVE);
		queueJob.setUser(true);
		queueJob.schedule();


	}

	protected Collection<StatusBean> getStatusBeans(final URI uri, final String queueName, final IProgressMonitor monitor) throws Exception {
		
		QueueConnectionFactory connectionFactory = ConnectionFactoryFacade.createConnectionFactory(uri);
		monitor.worked(1);
		QueueConnection qCon  = connectionFactory.createQueueConnection(); // This times out when the server is not there.
		QueueSession    qSes  = qCon.createQueueSession(false, Session.AUTO_ACKNOWLEDGE);
		Queue queue   = qSes.createQueue(queueName);
		qCon.start();
		
	    QueueBrowser qb = qSes.createBrowser(queue);
		monitor.worked(1);
    
	    @SuppressWarnings("rawtypes")
		Enumeration  e  = qb.getEnumeration();
	    
        Class        clazz  = getBeanClass();
		ObjectMapper mapper = new ObjectMapper();
		
		final Collection<StatusBean> list = new TreeSet<StatusBean>(new Comparator<StatusBean>() {
			@Override
			public int compare(StatusBean o1, StatusBean o2) {
				// Newest first!
		        long t1 = o2.getSubmissionTime();
		        long t2 = o1.getSubmissionTime();
		        return (t1<t2 ? -1 : (t1==t2 ? 0 : 1));
			}
		});

        while(e.hasMoreElements()) {
	    	Message m = (Message)e.nextElement();
	    	if (m==null) continue;
        	if (m instanceof TextMessage) {
            	TextMessage t = (TextMessage)m;
				final StatusBean bean = mapper.readValue(t.getText(), clazz);
              	list.add(bean);
        	}
	    }
        return list;
	}

	private Class getBeanClass() throws ClassNotFoundException {
	    String beanBundleName = getSecondaryIdAttribute("beanBundleName");
	    String beanClassName  = getSecondaryIdAttribute("beanClassName");
	    
	    @SuppressWarnings("rawtypes")
	    Bundle bundle = Platform.getBundle(beanBundleName);
		return bundle.loadClass(beanClassName);
	}

	protected void createColumns() {
		
		final TableViewerColumn name = new TableViewerColumn(viewer, SWT.LEFT);
		name.getColumn().setText("Name");
		name.getColumn().setWidth(260);
		name.setLabelProvider(new ColumnLabelProvider() {
			public String getText(Object element) {
				return ((StatusBean)element).getName();
			}
		});
		
		final TableViewerColumn status = new TableViewerColumn(viewer, SWT.LEFT);
		status.getColumn().setText("Status");
		status.getColumn().setWidth(140);
		status.setLabelProvider(new ColumnLabelProvider() {
			public String getText(Object element) {
				return ((StatusBean)element).getStatus().toString();
			}
		});

		final TableViewerColumn pc = new TableViewerColumn(viewer, SWT.CENTER);
		pc.getColumn().setText("Complete (%)");
		pc.getColumn().setWidth(120);
		pc.setLabelProvider(new ColumnLabelProvider() {
			public String getText(Object element) {
				try {
				    return NumberFormat.getPercentInstance().format(((StatusBean)element).getPercentComplete()/100d);
				} catch (Exception ne) {
					return "-";
				}
			}
		});

		final TableViewerColumn submittedDate = new TableViewerColumn(viewer, SWT.CENTER);
		submittedDate.getColumn().setText("Date Submitted");
		submittedDate.getColumn().setWidth(150);
		submittedDate.setLabelProvider(new ColumnLabelProvider() {
			public String getText(Object element) {
				try {
					return DateFormat.getDateTimeInstance().format(new Date(((StatusBean)element).getSubmissionTime()));
				} catch (Exception e) {
					return e.getMessage();
				}
			}
		});
		
		final TableViewerColumn message = new TableViewerColumn(viewer, SWT.LEFT);
		message.getColumn().setText("Message");
		message.getColumn().setWidth(150);
		message.setLabelProvider(new ColumnLabelProvider() {
			public String getText(Object element) {
				try {
					return ((StatusBean)element).getMessage();
				} catch (Exception e) {
					return e.getMessage();
				}
			}
		});
		
		final TableViewerColumn location = new TableViewerColumn(viewer, SWT.LEFT);
		location.getColumn().setText("Location");
		location.getColumn().setWidth(300);
		location.setLabelProvider(new ColumnLabelProvider() {
			public String getText(Object element) {
				try {
					final StatusBean bean = (StatusBean)element;
		            return bean.getRunDirectory();
				} catch (Exception e) {
					return e.getMessage();
				}
			}
			public Color getForeground(Object element) {
				return getSite().getShell().getDisplay().getSystemColor(SWT.COLOR_BLUE);
			}
		});
		
		final TableViewerColumn host = new TableViewerColumn(viewer, SWT.CENTER);
		host.getColumn().setText("Host");
		host.getColumn().setWidth(150);
		host.setLabelProvider(new ColumnLabelProvider() {
			public String getText(Object element) {
				try {
					return ((StatusBean)element).getHostName();
				} catch (Exception e) {
					return e.getMessage();
				}
			}
		});

		final TableViewerColumn user = new TableViewerColumn(viewer, SWT.CENTER);
		user.getColumn().setText("User Name");
		user.getColumn().setWidth(150);
		user.setLabelProvider(new ColumnLabelProvider() {
			public String getText(Object element) {
				try {
					return ((StatusBean)element).getUserName();
				} catch (Exception e) {
					return e.getMessage();
				}
			}
		});

	    MouseMoveListener cursorListener = new MouseMoveListener() {		
			@Override
			public void mouseMove(MouseEvent e) {
				Point pt = new Point(e.x, e.y);
				TableItem item = viewer.getTable().getItem(pt);
				if (item == null) {
					viewer.getTable().setCursor(null);
					return;
				}
				Rectangle rect = item.getBounds(5);
				if (rect.contains(pt)) {
					viewer.getTable().setCursor(Display.getDefault().getSystemCursor(SWT.CURSOR_HAND));
				} else {
					viewer.getTable().setCursor(null);
				}
				
			}
		};
        viewer.getTable().addMouseMoveListener(cursorListener);
 
        MouseAdapter mouseClick = new MouseAdapter() {
			@Override
			public void mouseDown(MouseEvent e) {
			    Point pt = new Point(e.x, e.y);
				TableItem item = viewer.getTable().getItem(pt);
				if (item == null) return;
				Rectangle rect = item.getBounds(5);
				if (rect.contains(pt)) {
					
					final StatusBean bean = (StatusBean)item.getData();
					openResults(bean);
				}
			}
        };
        
        viewer.getTable().addMouseListener(mouseClick);

	}

	/**
	 * You can override this method to provide custom opening of
	 * results if required.
	 * 
	 * @param bean
	 */
	protected void openResults(StatusBean bean) {
		
		if (bean == null) return;
		try {
			final IConfigurationElement[] c = Platform.getExtensionRegistry().getConfigurationElementsFor("org.dawnsci.commandserver.ui.resultsOpenHandler");
			if (c!=null) {
				for (IConfigurationElement i : c) {
					final IResultOpenHandler handler = (IResultOpenHandler)i.createExecutableExtension("class");
					if (handler.isHandled(bean)) {
						boolean ok = handler.open(bean);
						if (ok) return;
					}
				}
			}
		} catch (Exception ne) {
			ErrorDialog.openError(getSite().getShell(), "Internal Error", "Cannot open "+bean.getRunDirectory()+" normally, will show directory instead.\n\nPlease contact your support representative.", 
					new Status(IStatus.ERROR, Activator.PLUGIN_ID, ne.getMessage()));
		}

		openDirectory(bean);
	}

	private void openDirectory(StatusBean bean) {
		try {
			final IWorkbenchPage page = EclipseUtils.getPage();
			
			final File fdir = new File(CmdUtils.getSanitizedPath(bean.getRunDirectory()));
			if (!fdir.exists()){
				MessageDialog.openConfirm(getSite().getShell(), "Directory Not There", "The directory '"+bean.getRunDirectory()+"' has been moved or deleted.\n\nPlease contact your support representative.");
			    return;
			}
			
			if (CmdUtils.isWindowsOS()) { // Open inside DAWN
				final String         dir  = fdir.getAbsolutePath();		
				IEditorDescriptor desc = PlatformUI.getWorkbench().getEditorRegistry().getDefaultEditor(dir+"/fred.html");
				final IEditorInput edInput = EclipseUtils.getExternalFileStoreEditorInput(dir);
				page.openEditor(edInput, desc.getId());
				
			} else { // Linux cannot be relied on to open the browser on a directory.
				CmdUtils.browse(fdir);
			}
			
		} catch (Exception e1) {
			ErrorDialog.openError(getSite().getShell(), "Internal Error", "Cannot open "+bean.getRunDirectory()+".\n\nPlease contact your support representative.", 
					new Status(IStatus.ERROR, Activator.PLUGIN_ID, e1.getMessage()));
		}
	}

	@Override
	public void setFocus() {
		if (!viewer.getTable().isDisposed()) {
			viewer.getTable().setFocus();
		}
	}


	private String getTopicName() {
		final String topicName = getSecondaryIdAttribute("topicName");
		if (topicName != null) return topicName;
		return "scisoft.default.STATUS_TOPIC";
	}

    protected URI getUri() throws Exception {
		final String uri = getSecondaryIdAttribute("uri");
		if (uri != null) return new URI(uri.replace("%3A", ":"));
		return new URI(getCommandPreference(CommandConstants.JMS_URI));
	}
    
    protected String getUserName() {
		final String name = getSecondaryIdAttribute("userName");
		if (name != null) return name;
		return System.getProperty("user.name");
	}
   
    protected String getCommandPreference(String key) {
		final IPreferenceStore store = Activator.getDefault().getPreferenceStore();
    	return store.getString(key);
    }

	protected String getQueueName() {
		final String qName =  getSecondaryIdAttribute("queueName");
		if (qName != null) return qName;
		return "scisoft.default.STATUS_QUEUE";
	}
	
	protected String getSubmissionQueueName() {
		final String qName =  getSecondaryIdAttribute("submissionQueueName");
		if (qName != null) return qName;
		return "scisoft.default.SUBMISSION_QUEUE";
	}
	
	private String getSecondaryIdAttribute(String key) {
		if (idProperties!=null) return idProperties.getProperty(key);
		if (getViewSite()==null) return null;
		final String secondId = getViewSite().getSecondaryId();
		if (secondId == null) return null;
		idProperties = PropUtils.parseString(secondId);
		return idProperties.getProperty(key);
	}

	public static String createId(final String beanBundleName, final String beanClassName, final String queueName, final String topicName, final String submissionQueueName) {
		
		final StringBuilder buf = new StringBuilder();
		buf.append(ID);
		buf.append(":");
		buf.append(createSecondaryId(beanBundleName, beanClassName, queueName, topicName, submissionQueueName));
		return buf.toString();
	}
	
	public static String createSecondaryId(final String beanBundleName, final String beanClassName, final String queueName, final String topicName, final String submissionQueueName) {
		
		final StringBuilder buf = new StringBuilder();
		append(buf, "beanBundleName",      beanBundleName);
		append(buf, "beanClassName",       beanClassName);
		append(buf, "queueName",           queueName);
		append(buf, "topicName",           topicName);
		append(buf, "submissionQueueName", submissionQueueName);
		return buf.toString();
	}

	private static void append(StringBuilder buf, String name, String value) {
		buf.append(name);
		buf.append("=");
		buf.append(value);
		buf.append(";");
	}
}
