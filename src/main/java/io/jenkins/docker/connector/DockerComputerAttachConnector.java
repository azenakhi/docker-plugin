package io.jenkins.docker.connector;

import static com.nirima.jenkins.plugins.docker.DockerTemplateBase.splitAndFilterEmpty;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.ExecCreateCmd;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.google.common.base.Joiner;

import hudson.EnvVars;
import hudson.Extension;
import hudson.Util;
import hudson.model.Descriptor;
import hudson.model.TaskListener;
import hudson.remoting.Channel;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.SlaveComputer;
import io.jenkins.docker.client.DockerAPI;
import io.jenkins.docker.client.DockerMultiplexedInputStream;
import jenkins.model.Jenkins;

import org.apache.commons.lang.StringUtils;
import org.jenkinsci.Symbol;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.Serializable;
import java.net.Socket;
import java.util.Arrays;
import java.util.Collection;
import java.util.Objects;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;

/**
 * @author <a href="mailto:nicolas.deloof@gmail.com">Nicolas De Loof</a>
 */
public class DockerComputerAttachConnector extends DockerComputerConnector implements Serializable {

    @CheckForNull
    private String user;
    @CheckForNull
    private String javaExe;
    @CheckForNull
    private String[] jvmArgs;
    @CheckForNull
    private String[] entryPointCmd;

    @DataBoundConstructor
    public DockerComputerAttachConnector() {
    }

    public DockerComputerAttachConnector(String user) {
        this.user = user;
    }

    @Nonnull
    public String getUser() {
        return user==null ? "" : user;
    }

    @DataBoundSetter
    public void setUser(String user) {
        if ( user==null || user.trim().isEmpty()) {
            this.user = null;
        } else {
            this.user = user;
        }
    }

    @Nonnull
    public String getJavaExe() {
        return javaExe==null ? "" : javaExe;
    }

    @DataBoundSetter
    public void setJavaExe(String javaExe) {
        if ( javaExe==null || javaExe.trim().isEmpty()) {
            this.javaExe = null;
        } else {
            this.javaExe = javaExe;
        }
    }

    @CheckForNull
    public String[] getEntryPointCmd(){
        return entryPointCmd;
    }

    @Nonnull
    public String getEntryPointCmdString() {
        if (entryPointCmd == null) return "";
        return Joiner.on("\n").join(entryPointCmd);
    }

    @DataBoundSetter
    public void setEntryPointCmdString(String entryPointCmdString) {
        setEntryPointCmd(splitAndFilterEmpty(entryPointCmdString, "\n"));
    }

    public void setEntryPointCmd(String[] entryPointCmd) {
        if (entryPointCmd == null || entryPointCmd.length == 0) {
            this.entryPointCmd = null;
        } else {
            this.entryPointCmd = entryPointCmd;
        }
    }

    @CheckForNull
    public String[] getJvmArgs(){
        return jvmArgs;
    }

    @Nonnull
    public String getJvmArgsString() {
        if (jvmArgs == null) return "";
        return Joiner.on("\n").join(jvmArgs);
    }

    @DataBoundSetter
    public void setJvmArgsString(String jvmArgsString) {
        setJvmArgs(splitAndFilterEmpty(jvmArgsString, "\n"));
    }

    public void setJvmArgs(String[] jvmArgs) {
        if (jvmArgs == null || jvmArgs.length == 0) {
            this.jvmArgs = null;
        } else {
            this.jvmArgs = jvmArgs;
        }
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = super.hashCode();
        result = prime * result + Arrays.hashCode(entryPointCmd);
        result = prime * result + Arrays.hashCode(jvmArgs);
        result = prime * result + Objects.hash(javaExe, user);
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!super.equals(obj)) {
            return false;
        }
        DockerComputerAttachConnector other = (DockerComputerAttachConnector) obj;
        return Arrays.equals(entryPointCmd, other.entryPointCmd) && Objects.equals(javaExe, other.javaExe)
                && Arrays.equals(jvmArgs, other.jvmArgs) && Objects.equals(user, other.user);
    }

    @Override
    public void beforeContainerCreated(DockerAPI api, String workdir, CreateContainerCmd cmd) throws IOException, InterruptedException {
        ensureWaiting(cmd);
    }

    @Override
    public void afterContainerStarted(DockerAPI api, String workdir, String containerId) throws IOException, InterruptedException {
        try(final DockerClient client = api.getClient()) {
            injectRemotingJar(containerId, workdir, client);
        }
    }

    @Restricted(NoExternalUse.class)
    public enum ArgumentVariables {
        JavaExe("JAVA", "The java binary, e.g. java, /usr/bin/java etc."), //
        JvmArgs("JVM_ARGS", "Any arguments for the JVM itself, e.g. -Xmx250m."), //
        JarName("JAR_NAME", "The name of the jar file the node must run, e.g. slave.jar."), //
        RemoteFs("FS_DIR",
                "The filesystem folder in which the slave process is to be run."), //
        JenkinsUrl("JENKINS_URL", "The Jenkins root URL.");
        private final String name;
        private final String description;

        ArgumentVariables(String name, String description) {
            this.name = name;
            this.description = description;
        }

        public String getName() {
            return name;
        }

        public String getDescription() {
            return description;
        }
    }

    private static final String DEFAULT_ENTRY_POINT_CMD_STRING = "${" + ArgumentVariables.JavaExe.getName() + "}\n"
            + "${" + ArgumentVariables.JvmArgs.getName() + "}\n"
            + "-jar\n"
            + "${" + ArgumentVariables.RemoteFs.getName() + "}/${" + ArgumentVariables.JarName.getName() + "}\n"
            + "-noReconnect\n"
            + "-noKeepAlive\n"
            + "-slaveLog\n"
            + "${" + ArgumentVariables.RemoteFs.getName() + "}/agent.log";

    @Override
    protected ComputerLauncher createLauncher(DockerAPI api, String workdir, InspectContainerResponse inspect, TaskListener listener) throws IOException, InterruptedException {
        return new DockerAttachLauncher(api, inspect.getId(), getUser(), workdir, getJavaExe(), getJvmArgsString(), getEntryPointCmdString());
    }


    @Extension(ordinal = 100) @Symbol("attach")
    public static class DescriptorImpl extends Descriptor<DockerComputerConnector> {

        public String getDefaultJavaExe() {
            return "java";
        }

        public String getJavaExeVariableName() {
            return ArgumentVariables.JavaExe.name;
        }

        public String getJvmArgsVariableName() {
            return ArgumentVariables.JvmArgs.name;
        }

        public Collection<ArgumentVariables> getEntryPointCmdVariables() {
            return Arrays.asList(ArgumentVariables.values());
        }

        public Collection<String> getDefaultEntryPointCmd() {
            final String[] args = splitAndFilterEmpty(DEFAULT_ENTRY_POINT_CMD_STRING, "\n");
            return Arrays.asList(args);
        }

        @Override
        public String getDisplayName() {
            return "Attach Docker container";
        }
    }

    private static class DockerAttachLauncher extends ComputerLauncher {
        private final DockerAPI api;
        private final String containerId;
        private final String user;
        private final String remoteFs;
        private final String javaExe;
        private final String jvmArgs;
        private final String entryPointCmd;

        private DockerAttachLauncher(DockerAPI api, String containerId, String user, String remoteFs, String javaExe, String jvmArgs, String entryPointCmd) {
            this.api = api;
            this.containerId = containerId;
            this.user = user;
            this.remoteFs = remoteFs;
            this.javaExe = javaExe;
            this.jvmArgs = jvmArgs;
            this.entryPointCmd = entryPointCmd;
        }

        @Override
        public void launch(final SlaveComputer computer, TaskListener listener) throws IOException, InterruptedException {
            final PrintStream logger = computer.getListener().getLogger();
            final String jenkinsUrl = Jenkins.getInstance().getRootUrl();
            final String effectiveJavaExe = javaExe.isEmpty() ? "java" : javaExe;
            final String effectiveJvmArgs = jvmArgs.isEmpty() ? "" : jvmArgs;
            final EnvVars knownVariables = calculateVariablesForVariableSubstitution(effectiveJavaExe, effectiveJvmArgs, remoting.getName(), remoteFs, jenkinsUrl);
            final String effectiveEntryPointCmdString = StringUtils.isNotBlank(entryPointCmd) ? entryPointCmd : DEFAULT_ENTRY_POINT_CMD_STRING;
            final String resolvedEntryPointCmdString = Util.replaceMacro(effectiveEntryPointCmdString, knownVariables);
            final String[] resolvedEntryPointCmd = splitAndFilterEmpty(resolvedEntryPointCmdString, "\n");
            logger.println("Connecting to docker container " + containerId + ", running command " + Joiner.on(" ").join(resolvedEntryPointCmd));

            final String execId;
            try(final DockerClient client = api.getClient()) {
                final ExecCreateCmd cmd = client.execCreateCmd(containerId)
                        .withAttachStdin(true)
                        .withAttachStdout(true)
                        .withAttachStderr(true)
                        .withTty(false)
                        .withCmd(resolvedEntryPointCmd);
                if (StringUtils.isNotBlank(user)) {
                    cmd.withUser(user);
                }
                final ExecCreateCmdResponse exec = cmd.exec();
                execId = exec.getId();
            }

            String js = "{ \"Detach\": false, \"Tty\": false }";

            Socket socket = api.getSocket();

            final OutputStream out = socket.getOutputStream();
            final InputStream in = socket.getInputStream();

            final PrintWriter w = new PrintWriter(out);
            w.println("POST /v1.32/exec/" + execId + "/start HTTP/1.1");
            w.println("Host: docker.sock");
            w.println("Content-Type: application/json");
            w.println("Upgrade: tcp");
            w.println("Connection: Upgrade");
            w.println("Content-Length: " + js.length());
            w.println();
            w.println(js);
            w.flush();

            // read HTTP response headers
            String line = readLine(in);
            logger.println(line);
            if (! line.startsWith("HTTP/1.1 101 ")) {   // Switching Protocols
                throw new IOException("Unexpected HTTP response status line " + line);
            }

            // Skip HTTP header
            while ((line = readLine(in)).length() > 0) {
                if (line.length() == 0) break; // end of header
                logger.println(line);
            }

            final InputStream demux = new DockerMultiplexedInputStream(in);

            computer.setChannel(demux, out, listener, new Channel.Listener() {
                @Override
                public void onClosed(Channel channel, IOException cause) {
                    // Bye!
                }
            });

        }

        private static EnvVars calculateVariablesForVariableSubstitution(final String javaExe, final String jvmArgs, final String jarName, final String remoteFs,
                final String jenkinsUrl) throws IOException, InterruptedException {
            final EnvVars knownVariables = new EnvVars();
            final Jenkins j = Jenkins.getInstance();
            addEnvVars(knownVariables, j.getGlobalNodeProperties());
            for (final ArgumentVariables v : ArgumentVariables.values()) {
                // This switch statement MUST handle all possible
                // values of v.
                final String argValue;
                switch (v) {
                    case JavaExe :
                        argValue = javaExe;
                        break;
                    case JvmArgs :
                        argValue = jvmArgs;
                        break;
                    case JarName :
                        argValue = jarName;
                        break;
                    case RemoteFs :
                        argValue = remoteFs;
                        break;
                    case JenkinsUrl :
                        argValue = jenkinsUrl;
                        break;
                    default :
                        final String msg = "Internal code error: Switch statement is missing \"case " + v.name()
                                + " : argValue = ... ; break;\" code.";
                        // If this line throws an exception then it's because
                        // someone has added a new variable to the enum without
                        // adding code above to handle it.
                        // The two have to be kept in step in order to
                        // ensure that the help text stays in step.
                        throw new RuntimeException(msg);
                }
                addEnvVar(knownVariables, v.getName(), argValue);
            }
            return knownVariables;
        }

        private String readLine(InputStream in) throws IOException {
            StringBuilder s = new StringBuilder();
            int c;
            while((c = in.read()) > 0) {
                if (c == '\r') break; // EOL
                s.append((char) c);
            }
            in.read(); // \n
            return s.toString();
        }
    }
}
