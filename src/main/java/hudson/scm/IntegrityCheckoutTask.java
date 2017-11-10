/*******************************************************************************
 * Contributors:
 *     PTC 2016
 *******************************************************************************/
package hudson.scm;

import java.io.File;
import java.io.IOException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.List;
import java.util.Vector;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.jenkinsci.remoting.RoleChecker;
import org.jenkinsci.remoting.RoleSensitive;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.mks.api.response.APIException;

import hudson.FilePath;
import hudson.FilePath.FileCallable;
import hudson.model.TaskListener;
import hudson.remoting.VirtualChannel;
import hudson.scm.api.ExceptionHandler;
import hudson.scm.api.session.APISession;
import hudson.scm.api.session.ISession;
import jenkins.security.Roles;

public class IntegrityCheckoutTask implements FileCallable<Boolean>
{
  private static final long serialVersionUID = 1240357991626897900L;
  private static final Logger LOGGER = Logger.getLogger(IntegritySCM.class.getSimpleName());
  private static final int CHECKOUT_TRESHOLD = 5000;
  private final List<Hashtable<CM_PROJECT, Object>> projectMembersList;
  private final List<String> dirList;
  private final String lineTerminator;
  private final boolean restoreTimestamp;
  private final boolean cleanCopy;
  private final String alternateWorkspaceDir;
  private final boolean fetchChangedWorkspaceFiles;
  private final TaskListener listener;
  private IntegrityConfigurable integrityConfig;
  // Checksum Hash
  private ConcurrentHashMap<String, String> checksumHash;
  // Counts
  private int addCount;
  private int updateCount;
  private int dropCount;
  private int fetchCount;
  private int checkoutThreadPoolSize;
  private int checkoutThreadTimeout;

  /**
   * Hudson supports building on distributed machines, and the SCM plugin must be able to be
   * executed on other machines than the master.
   * 
   * @param projectMembersList A list of all the members that are in this Integrity SCM project
   * @param dirList A list of all the unique directories in this Integrity SCM project
   * @param alternateWorkspaceDir Specifies an alternate location for checkout other than the
   *        default workspace
   * @param lineTerminator The line termination setting for this checkout operation
   * @param restoreTimestamp Toggles whether to use the current date/time or the original date/time
   *        for the member
   * @param cleanCopy Indicates whether or not the workspace needs to be cleaned up prior to
   *        checking out files
   * @param fetchChangedWorkspaceFiles Toggles whether or not to calculate checksums, so if changed
   *        then it will be overwritten
   * @param checkoutThreadPoolSize Number of parallel threads for the checkout
   * @param checkoutThreadTimeout Timeout in minutes per checkout thread
   * @param listener The Hudson task listener
   * @param integrityConfig Integrity Configuration Object
   */
  public IntegrityCheckoutTask(List<Hashtable<CM_PROJECT, Object>> projectMembersList,
      List<String> dirList, String alternateWorkspaceDir, String lineTerminator,
      boolean restoreTimestamp, boolean cleanCopy, boolean fetchChangedWorkspaceFiles,
      int checkoutThreadPoolSize, int checkoutThreadTimeout, TaskListener listener, IntegrityConfigurable integrityConfig)
  {
    this.projectMembersList = projectMembersList;
    this.dirList = dirList;
    this.alternateWorkspaceDir = alternateWorkspaceDir;
    this.lineTerminator = lineTerminator;
    this.restoreTimestamp = restoreTimestamp;
    this.cleanCopy = cleanCopy;
    this.fetchChangedWorkspaceFiles = fetchChangedWorkspaceFiles;
    this.listener = listener;
    this.integrityConfig = integrityConfig;
    this.addCount = 0;
    this.updateCount = 0;
    this.dropCount = 0;
    this.fetchCount = 0;
    this.checkoutThreadPoolSize = checkoutThreadPoolSize;
    this.checkoutThreadTimeout = checkoutThreadTimeout;
    this.checksumHash = new ConcurrentHashMap<String, String>();
    LOGGER.fine("Integrity Checkout Task Created!");
  }

  /**
   * Creates the folder structure for the project's contents allowing empty folders to be created
   * 
   * @param workspace
   */
  private void createFolderStructure(FilePath workspace)
  {
    Iterator<String> folders = dirList.iterator();
    while (folders.hasNext())
    {
      File dir = new File(workspace + folders.next());
      if (!dir.isDirectory())
      {
        LOGGER.fine("Creating folder: " + dir.getAbsolutePath());
        dir.mkdirs();
      }
    }
  }

  /**
   * Returns all the changes to the checksums that were performed
   * 
   * @return
   */
  public ConcurrentHashMap<String, String> getChecksumUpdates()
  {
    return checksumHash;
  }

  /**
   * Indicates that this task can be run slaves.
   * 
   * @param checker RoleChecker
   */
  public void checkRoles(RoleChecker checker) throws SecurityException
  {
    checker.check((RoleSensitive) this, Roles.SLAVE);
  }

  /**
   * This task wraps around the code necessary to checkout Integrity CM Members on remote machines
   */
  @Override
  public Boolean invoke(File workspaceFile, VirtualChannel channel) throws IOException
  {
    // Figure out where we should be checking out this project
    File checkOutDir = (null != alternateWorkspaceDir && alternateWorkspaceDir.length() > 0)
        ? new File(alternateWorkspaceDir) : workspaceFile;
    // Convert the file object to a hudson FilePath (helps us with workspace.deleteContents())
    FilePath workspace = new FilePath(checkOutDir.isAbsolute() ? checkOutDir
        : new File(workspaceFile.getAbsolutePath() + IntegritySCM.FS + checkOutDir.getPath()));

    listener.getLogger().println("Checkout directory is " + workspace);

    final ThreadLocalOpenFileHandler openFileHandler = new ThreadLocalOpenFileHandler();
    final ThreadLocalAPISession generateAPISession = new ThreadLocalAPISession(integrityConfig);
    final ThreadFactory threadFactory =
        new ThreadFactoryBuilder().setNameFormat("Integrity-Checkout-Task-%d").build();
    ExecutorService executor = Executors.newFixedThreadPool(checkoutThreadPoolSize, threadFactory);
    @SuppressWarnings("rawtypes")
    final List<Future> coThreads = new ArrayList<Future>();
    // If we got here, then APISession was created successfully!
    try
    {
      // Keep count of the open file handles generated on the server
      if (cleanCopy)
      {
        listener.getLogger()
            .println("A clean copy is requested; deleting contents of " + workspace);
        LOGGER.fine("Deleting contents of workspace " + workspace);
        workspace.deleteContents();
        listener.getLogger().println("Populating clean workspace...");
      }

      // Create an empty folder structure first
      createFolderStructure(workspace);

      // Perform a synchronize of each file in the member list...
      for (Iterator<Hashtable<CM_PROJECT, Object>> it = projectMembersList.iterator(); it
          .hasNext();)
      {
        Hashtable<CM_PROJECT, Object> memberInfo = it.next();
        short deltaFlag = (null == memberInfo.get(CM_PROJECT.DELTA) ? -1
            : Short.valueOf(memberInfo.get(CM_PROJECT.DELTA).toString()));
        File targetFile = new File(workspace + memberInfo.get(CM_PROJECT.RELATIVE_FILE).toString());
        String memberName = memberInfo.get(CM_PROJECT.NAME).toString();
        String memberID = memberInfo.get(CM_PROJECT.MEMBER_ID).toString();
        String memberRev = memberInfo.get(CM_PROJECT.REVISION).toString();
        Timestamp memberTimestamp = (Timestamp) memberInfo.get(CM_PROJECT.TIMESTAMP);
        String configPath = memberInfo.get(CM_PROJECT.CONFIG_PATH).toString();
        String checksum = (null == memberInfo.get(CM_PROJECT.CHECKSUM) ? ""
            : memberInfo.get(CM_PROJECT.CHECKSUM).toString());

        if (cleanCopy && deltaFlag != 3)
        {
          LOGGER.fine("Attempting to checkout file: " + targetFile.getAbsolutePath()
              + " at revision " + memberRev);
          coThreads.add(executor
              .submit(new CheckOutTask(generateAPISession, openFileHandler, memberName, configPath,
                  memberID, memberRev, memberTimestamp, targetFile, fetchChangedWorkspaceFiles)));
          fetchCount++;
        } else if (deltaFlag == 0 && fetchChangedWorkspaceFiles && checksum.length() > 0)
        {
          if (!checksum.equals(IntegrityCMMember.getMD5Checksum(targetFile)))
          {
            LOGGER.fine("Attempting to restore changed workspace file: "
                + targetFile.getAbsolutePath() + " to revision " + memberRev);
            coThreads.add(executor.submit(new CheckOutTask(generateAPISession, openFileHandler,
                memberName, configPath, memberID, memberRev, memberTimestamp, targetFile, false)));
            fetchCount++;
          }
        } else if (deltaFlag == 1)
        {
          LOGGER.fine("Attempting to get new file: " + targetFile.getAbsolutePath()
              + " at revision " + memberRev);
          coThreads.add(executor
              .submit(new CheckOutTask(generateAPISession, openFileHandler, memberName, configPath,
                  memberID, memberRev, memberTimestamp, targetFile, fetchChangedWorkspaceFiles)));
          addCount++;
        } else if (deltaFlag == 2)
        {
          LOGGER.fine("Attempting to update file: " + targetFile.getAbsolutePath() + " to revision "
              + memberRev);
          coThreads.add(executor
              .submit(new CheckOutTask(generateAPISession, openFileHandler, memberName, configPath,
                  memberID, memberRev, memberTimestamp, targetFile, fetchChangedWorkspaceFiles)));
          updateCount++;
        } else if (deltaFlag == 3)
        {
          LOGGER.fine("Attempting to drop file: " + targetFile.getAbsolutePath()
              + " was at revision " + memberRev);
          dropCount++;
          if (targetFile.exists() && !targetFile.delete())
          {
            listener.getLogger()
                .println("Failed to clean up workspace file " + targetFile.getAbsolutePath() + "!");
            return false;
          }
        }
      }

      int checkoutMembers = 0;
      int previousCount = 0;
      int canceledMembers = 0;
      int totalMembers = coThreads.size();
      while (!coThreads.isEmpty())
      {
        @SuppressWarnings("rawtypes")
        Iterator<Future> iter = coThreads.iterator();
        while (iter.hasNext())
        {
          Future<?> future = iter.next();
          if (future.isCancelled())
          {
            listener.getLogger().println("Checkout thread " + future.toString() + " was cancelled");
            canceledMembers++;
            iter.remove();
          } else
          {
            // Look for the result of this thread's execution within project-specific checkout thread timeout
            try
            {
              future.get(checkoutThreadTimeout, TimeUnit.MINUTES);
            } catch(TimeoutException e) {
            	LOGGER.log(Level.SEVERE, "Timeout Exception caught :: ", e);
                listener.getLogger().println("A Timeout Exception was caught. Failed to checkout contents of file!");
                listener.getLogger().println(e.getMessage());
                return false;
            } catch (ExecutionException e)
            {
              listener.getLogger().println(e.getMessage());
              LOGGER.log(Level.SEVERE, "ExecutionException", e);
              StackTraceElement[] st = e.getStackTrace();
              for (int i = 0; i < st.length; i++)
              {
                LOGGER.severe("\tat " + st[i].getClassName() + "." + st[i].getMethodName() + "("
                    + st[i].getFileName() + ":" + st[i].getLineNumber() + ")");
              }

              if (null != e.getMessage() && e.getMessage()
                  .indexOf("Unbuffered entity enclosing request can not be repeated") > 0)
              {
                // ignore...
              } else
              {
                return false;
              }
            }

            checkoutMembers++;
            iter.remove();
          }
        }
        if (previousCount != (checkoutMembers + canceledMembers))
        {
          LOGGER.fine("Checkout process: " + checkoutMembers + " of " + totalMembers
              + (canceledMembers > 0 ? "(Canceled: " + canceledMembers + ")" : ""));
        }
        previousCount = checkoutMembers + canceledMembers;
        // Wait 2 seconds a check again if all threads are done
        Thread.sleep(2000);
      }

      // Lets advice the user that we've checked out all the members
      if (cleanCopy)
      {
        listener.getLogger().println("Successfully checked out " + fetchCount + " files!");
      } else
      {
        // Lets advice the user that we've performed the updates to the workspace
        listener.getLogger().println("Successfully updated workspace with "
            + (addCount + updateCount) + " updates and cleaned up " + dropCount + " files!");
        if (fetchChangedWorkspaceFiles && fetchCount > 0)
        {
          listener.getLogger().println("Additionally, a total of " + fetchCount
              + " files were restored to their original repository state!");
        }
      }
    } catch (InterruptedException iex)
    {
      LOGGER.severe("Interrupted Exception caught...");
      listener.getLogger().println("An Interrupted Exception was caught!");
      LOGGER.severe(iex.getMessage());
      listener.getLogger().println(iex.getMessage());
      listener.getLogger().println("Failed to clean up workspace (" + workspace + ") contents!");
      return false;
    } finally {
      listener.getLogger().println("Terminating checkout threads");
      if(executor != null)
        executor.shutdown();
      if( generateAPISession != null )
        generateAPISession.remove();
    }

    // If we got here, everything is good on the checkout...
    return true;
  }
  
  /**
   * Nested class to manage the APISessions for the checkout thread pool
   */
  private static class ThreadLocalAPISession extends ThreadLocal<ISession>
  {
    IntegrityConfigurable integrityConfig;
    // Using a thread safe Vector instead of a List
    private Vector<ISession> sessions = new Vector<ISession>();

    /**
     * Initialize our constructor with the all the information needed to create an APISession
     * 
     * @param ipHost Integration Point host name
     * @param ipPortNum Integration Point port
     * @param host Integrity Server host name
     * @param portNum Integrity Server port
     * @param user Integrity Server user id
     * @param paswd Integrity Server user's password
     * @param secure Flag to determine whether or not secure sockets are in use
     */
    public ThreadLocalAPISession(IntegrityConfigurable integrityConfig)
    {
      this.integrityConfig = integrityConfig;
    }

    /**
     * Terminates all the active APISessions started by the thread pool
     */
    @Override
    public void remove()
    {
      for (ISession session : sessions)
      {
        try
        {
          LOGGER.fine("Terminating threaded API Sessions...");
          session.terminate();
        } catch (Exception ex)
        {
          LOGGER.fine("Error while shuting down thread API session: " + ex.getMessage());
        }
      }
      super.remove();
    }

    /**
     * Returns an initial APISession for this thread
     */
    @Override
    protected ISession initialValue()
    {
      ISession api = APISession.create(integrityConfig);
      if (null != api)
      {
        //api.ping();
        sessions.add(api);
        return api;
      } else
      {
        return null;
      }
    }
  }
  
  /**
   * Nested class to manage the open file handle count for the entire checkout process
   */
  private static class ThreadLocalOpenFileHandler extends ThreadLocal<Integer>
  {
    /**
     * Returns the initial value for the open file handle count
     */
    @Override
    protected Integer initialValue()
    {
      LOGGER.fine("Trying to retrieve initial value for open file handler");
      return new Integer(1);
    }
  }

  /**
   * Nested class that performs the actual checkout operation
   */
  private final class CheckOutTask implements Callable<Void>
  {
    private final ThreadLocalAPISession apiSession;
    private final ThreadLocalOpenFileHandler openFileHandler;
    private final String configPath;
    private final String memberID;
    private final String memberName;
    private final String memberRev;
    private final Timestamp memberTimestamp;
    private final File targetFile;
    private final boolean calculateChecksum;

    public CheckOutTask(ThreadLocalAPISession apiSession,
        ThreadLocalOpenFileHandler openFileHandler, String memberName, String configPath,
        String memberID, String memberRev, Timestamp memberTimestamp, File targetFile,
        boolean calculateChecksum)
    {
      this.apiSession = apiSession;
      this.openFileHandler = openFileHandler;
      this.configPath = configPath;
      this.memberID = memberID;
      this.memberName = memberName;
      this.memberRev = memberRev;
      this.memberTimestamp = memberTimestamp;
      this.targetFile = targetFile;
      this.calculateChecksum = calculateChecksum;
    }

    public Void call() throws Exception
    {
      ISession api = apiSession.get();
      if (null != api)
      {
        // Check to see if we need to release the APISession to clear some file handles
        LOGGER.fine("API open file handles: " + openFileHandler.get());
        if (openFileHandler.get() >= CHECKOUT_TRESHOLD)
        {
          LOGGER.fine("Checkout threshold reached for session " + api.toString()
              + ", refreshing API session");
          api.refreshAPISession();
          openFileHandler.set(1);
        }
        LOGGER.fine("Checkout on API thread: " + api.toString());
        try
        {
          IntegrityCMMember.checkout(api, configPath, memberID, memberRev, memberTimestamp,
              targetFile, restoreTimestamp, lineTerminator);
        } catch (APIException aex)
        {
          LOGGER.severe("API Exception caught...");
          ExceptionHandler eh = new ExceptionHandler(aex);
          LOGGER.severe(eh.getMessage());
          LOGGER.fine(eh.getCommand() + " returned exit code " + eh.getExitCode());
          throw new Exception(eh.getMessage());
        }

        openFileHandler.set(openFileHandler.get() + 1);
        if (calculateChecksum)
        {
          checksumHash.put(memberName, IntegrityCMMember.getMD5Checksum(targetFile));
        }
      } else
      {
        throw new Exception("Failed to create APISession!");
      }
      return null;
    }

  }
}
