package com.veertu.plugin.anka;

import com.veertu.ankaMgmtSdk.AnkaNotFoundException;
import com.veertu.ankaMgmtSdk.exceptions.AnkaMgmtException;
import hudson.model.Node;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.SynchronousNonBlockingStepExecution;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.ExecutionException;


public class DynamicSlaveStepExecution extends SynchronousNonBlockingStepExecution<String> {


    private final transient DynamicSlaveProperties properties;
    private final transient CreateDynamicAnkaNodeStep nodeStep;

    public DynamicSlaveStepExecution(CreateDynamicAnkaNodeStep nodeStep, StepContext context) {
        super(context);
        this.nodeStep = nodeStep;
        this.properties = nodeStep.getDynamicSlaveProperties();
    }


    @Override
    protected String run() throws Exception {
        AnkaMgmtCloud cloud = AnkaMgmtCloud.getCloudThatHasImage(this.properties.getMasterVmId());
        if (cloud == null) {
            throw new AnkaNotFoundException("no available cloud with image " + this.properties.getMasterVmId());
        }
        String label = UUID.randomUUID().toString();
        AnkaOnDemandSlave slave = null;
        int timeoutMillis = this.nodeStep.getTimeout() * 1000;
        long startTime = System.currentTimeMillis();
        int round = 1;
        while (true) {
            try {
                slave = cloud.StartNewDynamicSlave(this.properties, label);
                break;
            } catch (InterruptedException | IOException | AnkaMgmtException e) {
                if ((System.currentTimeMillis() - startTime) < timeoutMillis){
                    Thread.sleep(1000 * (round * round ));
                    round++;
                    continue;
                }
                throw e;
            }

        }
        slave.setMode(Node.Mode.EXCLUSIVE);
        Jenkins.getInstance().addNode(slave);
        startTime = System.currentTimeMillis(); // fetch starting time
        while (true) {
            try {
                AnkaMgmtCloud.Log("trying to init slave %s on vm", slave.getDisplayName());
                AnkaPlannedNode.tryToCallSlave(slave);
                break;
            }
            catch (ExecutionException e) {
                if ((System.currentTimeMillis() - startTime) < slave.launchTimeout * 1000){
                    AnkaMgmtCloud.Log("caught ExecutionException when trying to start %s " +
                            "sleeping for 1 seconds to retry", slave.getNodeName());
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException err) {
                        continue; // prevent interrupted exceptions during slave init
                    }
                    continue;
                }
                AnkaMgmtCloud.Log("Failed to connect to slave %s", slave.getNodeName());
                slave.terminate();
                throw e;
            }
            catch (Exception e) {
                AnkaMgmtCloud.Log("vm quit unexpectedly for slave %s", slave.getNodeName());
                slave.terminate();
                throw e;
            }
        }
        return label;
    }

}
