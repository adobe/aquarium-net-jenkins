package com.adobe.ci.aquarium.net;

import hudson.model.Computer;
import hudson.model.Executor;
import hudson.model.Queue;
import hudson.security.ACL;
import hudson.security.Permission;
import hudson.slaves.AbstractCloudComputer;
import org.acegisecurity.Authentication;
import java.util.logging.Level;
import java.util.logging.Logger;

public class AquariumComputer extends AbstractCloudComputer<AquariumSlave> {
    private static final Logger LOG = Logger.getLogger(AquariumComputer.class.getName());

    private boolean launching;

    public AquariumComputer(AquariumSlave slave) {
        super(slave);
    }

    @Override
    public void taskAccepted(Executor executor, Queue.Task task) {
        super.taskAccepted(executor, task);
        Queue.Executable exec = executor.getCurrentExecutable();
        LOG.log(Level.INFO, " Computer {0} accepted task {1}", new Object[] {this, exec});
    }

    @Override
    public void taskCompleted(Executor executor, Queue.Task task, long durationMS) {
        Queue.Executable exec = executor.getCurrentExecutable();
        LOG.log(Level.FINE, " Computer {0} completed task {1}", new Object[] {this, exec});

        setAcceptingTasks(false);
        super.taskCompleted(executor, task, durationMS);
        done();
    }

    private void done() {
        // Terminate the node
        try {
            AquariumSlave node = getNode();
            if( node == null ) {
                LOG.log(Level.WARNING, "Unable to terminate null node: " + getNode());
                setAcceptingTasks(true);
                return;
            }
            node.terminate();
        } catch( Exception ex ) {
            LOG.log(Level.WARNING, "Unable to terminate node due to exception: " + getNode(), ex);
        }
    }

    @Override
    public void taskCompletedWithProblems(Executor executor, Queue.Task task, long durationMS, Throwable problems) {
        setAcceptingTasks(false);
        super.taskCompletedWithProblems(executor, task, durationMS, problems);
        Queue.Executable exec = executor.getCurrentExecutable();
        LOG.log(Level.INFO, " Computer {0} completed task {1} with problems", new Object[] {this, exec});
        done();
    }

    @Override
    public String toString() {
        return String.format("AquariumComputer name: %s slave: %s", getName(), getNode());
    }

    @Override
    public ACL getACL() {
        final ACL base = super.getACL();
        return new ACL() {
            @Override
            public boolean hasPermission(Authentication a, Permission permission) {
                return permission == Computer.CONFIGURE ? false : base.hasPermission(a,permission);
            }
        };
    }

    public void setLaunching(boolean launching) {
        this.launching = launching;
    }

    public boolean isLaunching() {
        return launching;
    }

    @Override
    public void setAcceptingTasks(boolean acceptingTasks) {
        super.setAcceptingTasks(acceptingTasks);
        if (acceptingTasks) {
            launching = false;
        }
    }
}
