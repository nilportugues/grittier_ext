package com.maxdemarzi.search;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.maxdemarzi.Labels;
import com.maxdemarzi.RelationshipTypes;
import com.maxdemarzi.users.Users;
import org.codehaus.jackson.map.ObjectMapper;
import org.neo4j.collection.primitive.PrimitiveLongIterator;
import org.neo4j.graphdb.*;
import org.neo4j.kernel.api.ReadOperations;
import org.neo4j.kernel.api.exceptions.index.IndexNotFoundKernelException;
import org.neo4j.kernel.api.exceptions.schema.SchemaRuleNotFoundException;
import org.neo4j.kernel.api.index.IndexDescriptor;
import org.neo4j.kernel.impl.api.KernelStatement;
import org.neo4j.kernel.impl.core.ThreadToStatementContextBridge;
import org.neo4j.kernel.impl.storageengine.impl.recordstorage.RecordStorageEngine;
import org.neo4j.kernel.impl.store.StoreAccess;
import org.neo4j.kernel.internal.GraphDatabaseAPI;
import org.neo4j.storageengine.api.schema.IndexReader;

import javax.ws.rs.DefaultValue;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static com.maxdemarzi.Properties.*;
import static com.maxdemarzi.Time.getLatestTime;
import static com.maxdemarzi.likes.Likes.userLikesPost;
import static com.maxdemarzi.posts.Posts.getAuthor;
import static com.maxdemarzi.posts.Posts.userRepostedPost;
import static java.util.Collections.reverseOrder;

@Path("/search")
public class Search {
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private static GraphDatabaseAPI dbapi;
    private static int postLabelId;
    private static int statusPropertyId;

    // Cache
    private static LoadingCache<String, ArrayList<Long>> searches = Caffeine.newBuilder()
            .maximumSize(50_000)
            .expireAfterWrite(1, TimeUnit.DAYS)
            .refreshAfterWrite(1, TimeUnit.MINUTES)
            .build(Search::performSearch);

    private static ArrayList<Long> performSearch(String term) throws SchemaRuleNotFoundException, IndexNotFoundKernelException {
        ThreadToStatementContextBridge ctx = dbapi.getDependencyResolver().resolveDependency(ThreadToStatementContextBridge.class);
        KernelStatement st = (KernelStatement)ctx.get();
        ReadOperations ops = st.readOperations();
        IndexDescriptor descriptor = ops.indexGetForLabelAndPropertyKey(postLabelId, statusPropertyId);
        IndexReader reader = st.getStoreStatement().getIndexReader(descriptor);

        PrimitiveLongIterator hits = reader.containsString(term);
        ArrayList<Long> results = new ArrayList<>();
        while(hits.hasNext()) {
            results.add(hits.next());
        }
        return results;
    }


    public Search(@Context GraphDatabaseService db) throws NoSuchMethodException {
        this.dbapi = (GraphDatabaseAPI) db;
        try (Transaction tx = db.beginTx()) {
            ThreadToStatementContextBridge ctx = dbapi.getDependencyResolver().resolveDependency(ThreadToStatementContextBridge.class);
            ReadOperations ops = ctx.get().readOperations();
            postLabelId = ops.labelGetForName(Labels.Post.name());
            statusPropertyId = ops.propertyKeyGetForName(STATUS);
            tx.success();
        }
    }

    @GET
    public Response getSearch(@QueryParam("q") final String q,
                              @QueryParam("limit") @DefaultValue("25") final Integer limit,
                              @QueryParam("since") final Long since,
                              @QueryParam("username") final String username,
                              @Context GraphDatabaseService db) throws SchemaRuleNotFoundException, IndexNotFoundKernelException, NoSuchMethodException, IOException {
        ArrayList<Map<String, Object>> results = new ArrayList<>();
        Long latest = getLatestTime(since);

        try (Transaction tx = db.beginTx()) {
            final Node user = Users.findUser(username, db);
            ArrayList<Long> postIds = searches.get(q);
            Queue<Node> posts = new PriorityQueue<>(Comparator.comparing(m -> (Long) m.getProperty(TIME), reverseOrder()));

            postIds.forEach(postId -> {
                Node post = db.getNodeById(postId);
                Long time = (Long)post.getProperty("time");
                if(time < latest) {
                    posts.add(post);
                }
            });

            int count = 0;
            while (count < limit && !posts.isEmpty()) {
                count++;
                Node post = posts.poll();
                Map<String, Object> properties = post.getAllProperties();
                Node author = getAuthor(post, (Long) properties.get(TIME));

                properties.put(USERNAME, author.getProperty(USERNAME));
                properties.put(NAME, author.getProperty(NAME));
                properties.put(HASH, author.getProperty(HASH));
                properties.put(LIKES, post.getDegree(RelationshipTypes.LIKES));
                properties.put(REPOSTS, post.getDegree(Direction.INCOMING)
                        - 1 // for the Posted Relationship Type
                        - post.getDegree(RelationshipTypes.LIKES)
                        - post.getDegree(RelationshipTypes.REPLIED_TO));
                if (user != null) {
                    properties.put(LIKED, userLikesPost(user, post));
                    properties.put(REPOSTED, userRepostedPost(user, post));
                }
                results.add(properties);
            }
        }

        return Response.ok().entity(objectMapper.writeValueAsString(results)).build();
    }


    @GET
    @Path("/latest")
    public Response getLatest(@QueryParam("limit") @DefaultValue("25") final Integer limit,
                              @QueryParam("since") final Long since,
                              @QueryParam("username") final String username,
                              @Context GraphDatabaseService db) throws IOException {
        ArrayList<Map<String, Object>> results = new ArrayList<>();
        Long latest = getLatestTime(since);

        try (Transaction tx = db.beginTx()) {
            final Node user = Users.findUser(username, db);

            RecordStorageEngine recordStorageEngine = dbapi.getDependencyResolver()
                    .resolveDependency(RecordStorageEngine.class);
            StoreAccess storeAccess = new StoreAccess(recordStorageEngine.testAccessNeoStores());
            Long highId = storeAccess.getRawNeoStores().getNodeStore().getHighestPossibleIdInUse();

            int counter = 0;
            while (counter < limit && highId > -1) {
                Node post;
                try {
                    post = db.getNodeById(highId);
                } catch (NotFoundException e) {
                    continue;
                } finally {
                    highId--;
                }

                if (post.getLabels().iterator().next().name().equals(Labels.Post.name())) {
                    Long time = (Long) post.getProperty("time");
                    if (time < latest) {
                        counter++;
                        Map<String, Object> properties = post.getAllProperties();
                        Node author = getAuthor(post, (Long) properties.get(TIME));

                        properties.put(USERNAME, author.getProperty(USERNAME));
                        properties.put(NAME, author.getProperty(NAME));
                        properties.put(HASH, author.getProperty(HASH));
                        properties.put(LIKES, post.getDegree(RelationshipTypes.LIKES));
                        properties.put(REPOSTS, post.getDegree(Direction.INCOMING)
                                - 1 // for the Posted Relationship Type
                                - post.getDegree(RelationshipTypes.LIKES)
                                - post.getDegree(RelationshipTypes.REPLIED_TO));
                        if (user != null) {
                            properties.put(LIKED, userLikesPost(user, post));
                            properties.put(REPOSTED, userRepostedPost(user, post));
                        }
                        results.add(properties);
                    }
                }
            }
        }
        return Response.ok().entity(objectMapper.writeValueAsString(results)).build();
    }


}
