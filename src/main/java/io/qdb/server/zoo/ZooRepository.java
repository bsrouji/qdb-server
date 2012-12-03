package io.qdb.server.zoo;

import com.google.common.eventbus.EventBus;
import io.qdb.server.JsonService;
import io.qdb.server.model.*;
import org.apache.zookeeper.*;
import org.apache.zookeeper.data.Stat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Keeps our meta data in ZooKeeper.
 */
@Singleton
public class ZooRepository implements Repository, Watcher {

    private static final Logger log = LoggerFactory.getLogger(ZooRepository.class);

    private final EventBus eventBus;
    private final JsonService jsonService;
    private final String root;
    private final ZooKeeper zk;
    private final String initialAdminPassword;

    private Date upSince;

    @Inject
    public ZooRepository(EventBus eventBus, JsonService jsonService,
                         @Named("zookeeper.connectString") String connectString,
                         @Named("zookeeper.sessionTimeout") int sessionTimeout,
                         @Named("clusterName") String clusterName,
                         @Named("initialAdminPassword") String initialAdminPassword)
            throws IOException {
        this.eventBus = eventBus;
        this.jsonService = jsonService;
        this.root = "/qdb/" + clusterName;
        this.initialAdminPassword = initialAdminPassword;
        log.info("Connecting to ZooKeeper(s) [" + connectString + "] sessionTimeout " + sessionTimeout);
        zk = new ZooKeeper(connectString, sessionTimeout, this);
    }

    @Override
    public void process(WatchedEvent event) {
        try {
            synchronized (this) {
                log.debug(event.toString());
                Event.KeeperState state = event.getState();
                if (state == Event.KeeperState.SyncConnected) {
                    log.info("Connected to ZooKeeper");
                    populateZoo();
                    upSince = new Date(); // only set this after zoo has been populated so we know if that failed
                }
            }
            eventBus.post(getStatus());
        } catch (Exception e) {
            log.error(e.toString(), e);
        }
    }

    private void populateZoo() throws KeeperException, InterruptedException, IOException {
        ensureNodeExists("/qdb");
        ensureNodeExists(root);
        ensureNodeExists(root + "/nodes");
        ensureNodeExists(root + "/databases");
        ensureNodeExists(root + "/queues");
        ensureNodeExists(root + "/users");
        if (findUser("admin") == null) {
            User admin = new User();
            admin.setId("admin");
            admin.setPasswordHash(initialAdminPassword);
            admin.setAdmin(true);
            createUser(admin);
            log.info("Created initial admin user");
        }
    }

    private boolean ensureNodeExists(String path) throws KeeperException, InterruptedException {
        try {
            zk.create(path, new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            return true;
        } catch (KeeperException.NodeExistsException ignore) {
            return false;
        }
    }

    @Override
    public synchronized Status getStatus() {
        Status ans = new Status();
        ZooKeeper.States state = zk.getState();
        ans.status = state.name();
        switch (state) {
            case CONNECTED:
                if (upSince == null) {  // failed to init our schema in ZK
                    ans.status = "Failed to created initial nodes in ZooKeeper";
                } else {
                    ans.up = true;
                    ans.upSince = upSince;
                }
                break;
            case CONNECTEDREADONLY:
                ans.readOnly = true;
                break;
        }
        return ans;
    }

    @Override
    public void create(Node node) throws IOException {
    }

    @Override
    public List<Node> findNodes() throws IOException {
        return null;
    }

    @Override
    public User findUser(String id) throws IOException {
        try {
            return jsonService.fromJson(zk.getData(root + "/users/" + id, false, new Stat()), User.class);
        } catch (KeeperException e) {
            if (e.code() == KeeperException.Code.NONODE) return null;
            throw new IOException(e.toString(), e);
        } catch (InterruptedException e) {
            throw new IOException(e.toString(), e);
        }
    }

    @Override
    public void createUser(User user) throws IOException {
        assert user.getId() != null;
        try {
            User u = (User)user.clone();
            u.setId(null);
            zk.create(root + "/users/" + user.getId(), jsonService.toJson(u),
                    ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        } catch (KeeperException.NodeExistsException e) {
            throw new ModelException("User [" + user.getId() + "] already exists");
        } catch (KeeperException e) {
            throw new IOException(e.toString(), e);
        } catch (InterruptedException e) {
            throw new IOException(e.toString(), e);
        }
    }

    @Override
    public List<User> findUsers(int offset, int limit) throws IOException {
        try {
            List<User> ans = new ArrayList<User>();
            for (String id : zk.getChildren(root + "/users", false)) {
                User u = new User();
                u.setId(id);
                ans.add(u);
            }
            return ans;
        } catch (KeeperException e) {
            throw new IOException(e.toString(), e);
        } catch (InterruptedException e) {
            throw new IOException(e.toString(), e);
        }
    }

    @Override
    public int countUsers() throws IOException {
        try {
            return zk.exists(root + "/users", false).getNumChildren();
        } catch (KeeperException e) {
            throw new IOException(e.toString(), e);
        } catch (InterruptedException e) {
            throw new IOException(e.toString(), e);
        }
    }

    private String getLastPart(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }

    @Override
    public List<Database> findDatabasesVisibleTo(User user) {
        return null;
    }

    @Override
    public Database findDatabase(String id) {
        return null;
    }

    @Override
    public List<Queue> findQueues(Database db) {
        return null;
    }

    @Override
    public Queue findQueue(Database db, String nameOrId) {
        return null;
    }
}
