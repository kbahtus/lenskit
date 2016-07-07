/*
 * LensKit, an open source recommender systems toolkit.
 * Copyright 2010-2014 LensKit Contributors.  See CONTRIBUTORS.md.
 * Work on LensKit has been funded by the National Science Foundation under
 * grants IIS 05-34939, 08-08692, 08-12148, and 10-17697.
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License for more
 * details.
 *
 * You should have received a copy of the GNU General Public License along with
 * this program; if not, write to the Free Software Foundation, Inc., 51
 * Franklin Street, Fifth Floor, Boston, MA 02110-1301, USA.
 */
package org.lenskit.data.dao.file;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ListMultimap;
import org.grouplens.lenskit.util.io.Describable;
import org.grouplens.lenskit.util.io.DescriptionWriter;
import org.grouplens.lenskit.util.io.LKFileUtils;
import org.lenskit.data.dao.DataAccessException;
import org.lenskit.data.dao.DataAccessObject;
import org.lenskit.data.dao.EntityCollectionDAOBuilder;
import org.lenskit.data.entities.*;
import org.lenskit.data.ratings.PreferenceDomain;
import org.lenskit.data.ratings.PreferenceDomainBuilder;
import org.lenskit.util.io.ObjectStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.inject.Provider;
import java.io.IOException;
import java.lang.ref.SoftReference;
import java.net.URI;
import java.net.URL;
import java.nio.file.Path;
import java.util.*;

/**
 * Layout and builder for DAOs backed by static files.  This is used to read CSV files
 * and the like; it controls a composite DAO that reads from files, caches them in
 * memory, and can compute some derived entities from others (e.g. extracting items
 * from the item IDs in a rating data set).
 */
public class StaticDataSource implements Provider<DataAccessObject>, Describable {
    private static final Logger logger = LoggerFactory.getLogger(StaticDataSource.class);

    private String name;
    private List<EntitySource> sources;
    private ListMultimap<EntityType, TypedName<?>> indexedAttributes;
    private transient volatile SoftReference<DataAccessObject> cachedDao;

    /**
     * Construct a new data layout object.
     */
    public StaticDataSource() {
        this(null);
    }

    /**
     * Construct a new data layout object.
     * @param name The name of the data source.
     */
    public StaticDataSource(String name) {
        this.name = name != null ? name : "<unnamed>";
        sources = new ArrayList<>();
        indexedAttributes = ArrayListMultimap.create();
    }

    /**
     * Get the name of this source.
     * @return The source name.
     */
    public String getName() {
        return name;
    }

    /**
     * Add a collection data source.
     * @param data The entities to add.
     */
    public void addSource(Collection<? extends Entity> data) {
        addSource(data, Collections.<String, Object>emptyMap());
    }

    /**
     * Add a collection data source.
     * @param data The entities to add.
     * @param metadata The entity source metadata.
     */
    public void addSource(Collection<? extends Entity> data, Map<String,Object> metadata) {
        sources.add(new CollectionEntitySource("<unnamed " + data.size() + ">", data, metadata));
    }

    /**
     * Add an entity source.
     * @param source The entity source to add.
     */
    public void addSource(EntitySource source) {
        sources.add(source);
    }

    /**
     * Index entities by an attribute.
     * @param type The entity type to index.
     * @param attr The attribute to index.
     */
    public void addIndex(EntityType type, TypedName<?> attr) {
        indexedAttributes.put(type, attr);
    }

    /**
     * Get the list of entity sources.
     * @return The list of entity sources.
     */
    public List<EntitySource> getSources() {
        return ImmutableList.copyOf(sources);
    }

    /**
     * Get the entity sources producing a particular type.
     * @param type The entity type to look for.
     * @return A list of sources producing entities of type {@code type}.
     */
    @Nonnull
    public List<EntitySource> getSourcesForType(EntityType type) {
        List<EntitySource> result = new ArrayList<>();
        for (EntitySource source: sources) {
            if (source.getTypes().contains(type)) {
                result.add(source);
            }
        }
        return result;
    }

    /**
     * Get the data access object. This method is thread-safe.
     * @return The access object.
     */
    @Override
    public DataAccessObject get() {
        SoftReference<DataAccessObject> cache = cachedDao;
        DataAccessObject dao = cache != null ? cache.get() : null;
        if (dao == null) {
            synchronized (this) {
                // did someone else make a DAO?
                cache = cachedDao;
                dao = cache != null ? cache.get() : null;
                if (dao == null) {
                    try {
                        dao = makeDAO();
                        cachedDao = new SoftReference<>(dao);
                    } catch (IOException e) {
                        throw new DataAccessException("cannot load data", e);
                    }
                }
            }
        }

        return dao;
    }

    @Nullable
    public PreferenceDomain getPreferenceDomain() {
        PreferenceDomain domain = null;
        for (EntitySource src: getSourcesForType(CommonTypes.RATING)) {
            Map<String,Object> meta = src.getMetadata();
            if (meta.containsKey("domain")) {
                if (domain != null) {
                    logger.warn("multiple rating sources have domains");
                }
                Map<String,Object> dom = (Map<String, Object>) meta.get("domain");
                PreferenceDomainBuilder pdb = new PreferenceDomainBuilder();
                pdb.setMinimum(((Number) dom.get("minimum")).doubleValue())
                   .setMaximum(((Number) dom.get("maximum")).doubleValue());
                Number prec = (Number) dom.get("precision");
                if (prec != null) {
                    pdb.setPrecision(prec.doubleValue());
                }
                domain = pdb.build();
            }
        }
        return domain;
    }

    private DataAccessObject makeDAO() throws IOException {
        Set<EntityType> types = new HashSet<>();

        EntityCollectionDAOBuilder builder = new EntityCollectionDAOBuilder();
        builder.addDefaultIndex(CommonAttributes.USER_ID);
        builder.addDefaultIndex(CommonAttributes.ITEM_ID);
        for (Map.Entry<EntityType,TypedName<?>> iae: indexedAttributes.entries()) {
            builder.addIndex(iae.getKey(), iae.getValue());
        }
        for (EntitySource source: sources) {
            try (ObjectStream<Entity> data = source.openStream()) {
                for (Entity e: data) {
                    builder.addEntity(e);
                    types.add(e.getType());
                }
            }
        }

        for (EntityType type: types) {
            EntityDefaults defaults = EntityDefaults.lookup(type);
            if (defaults == null) {
                continue;
            }
            for (EntityDerivation deriv: defaults.getDefaultDerivations()) {
                EntityType derived = deriv.getType();
                if (types.contains(derived)) {
                    continue;
                }
                TypedName<Long> column = deriv.getAttribute();
                logger.info("deriving entity type {} from {} (column {})",
                            derived, deriv.getSourceTypes(), column);
                builder.deriveEntities(derived, type, column);
            }
        }

        return builder.build();
    }

    @Override
    public String toString() {
        return String.format("%s: static file DAO with %d sources", name, sources.size());
    }

    @Override
    public void describeTo(DescriptionWriter writer) {
        writer.putField("name", name);
        writer.putList("sources", sources);
    }

    /**
     * Parse a JSON description of a data set.
     *
     * @param object The JSON object.
     * @return A DAO provider configured from the JSON data.
     */
    public static StaticDataSource fromJSON(JsonNode object, URI base) {
        return fromJSON(null, object, base);
    }

    /**
     * Parse a JSON description of a data set.
     *
     * @param object The JSON object.
     * @return A DAO provider configured from the JSON data.
     */
    public static StaticDataSource fromJSON(String name, JsonNode object, URI base) {
        if (name == null && object.has("name")) {
            name = object.get("name").asText();
        }
        StaticDataSource layout = new StaticDataSource(name);

        if (object.isArray()) {
            for (JsonNode source: object) {
                layout.addSource(EntitySources.fromJSON(null, source, base));
            }
        } else if (object.isObject()) {
            if (object.has("file") || object.has("type")) {
                // the whole object describes one data source
                layout.addSource(EntitySources.fromJSON(null, object, base));
            } else {
                // the object describes multiple data sources
                Iterator<Map.Entry<String, JsonNode>> iter = object.fields();
                while (iter.hasNext()) {
                    Map.Entry<String, JsonNode> entry = iter.next();
                    layout.addSource(EntitySources.fromJSON(entry.getKey(), entry.getValue(), base));
                }
            }
        } else {
            throw new IllegalArgumentException("manifest must be array or object");
        }

        return layout;
    }

    /**
     * Load a static file data set from a file.
     * @param path The file to load.
     * @return The data source.
     * @throws IOException If there is an error loading the data source.
     */
    public static StaticDataSource load(Path path) throws IOException {
        return load(path, path.getFileName().toString());
    }

    /**
     * Load a static file data set from a file.
     * @param path The file to load.
     * @param name The source name.
     * @return The data source.
     * @throws IOException If there is an error loading the data source.
     */
    public static StaticDataSource load(Path path, String name) throws IOException {
        URI uri = path.toAbsolutePath().toUri();
        JsonFactory factory = new YAMLFactory();
        ObjectMapper mapper = new ObjectMapper(factory);
        JsonNode node = mapper.readTree(path.toFile());
        return fromJSON(name, node, uri);
    }

    /**
     * Load a static file data set from a URI.
     * @param uri The URI to load.
     * @param name The source name.
     * @return The data source.
     * @throws IOException If there is an error loading the data source.
     */
    public static StaticDataSource load(URI uri, String name) throws IOException {
        URL url = uri.toURL();
        JsonFactory factory = new YAMLFactory();
        ObjectMapper mapper = new ObjectMapper(factory);
        JsonNode node = mapper.readTree(url);
        return fromJSON(name, node, uri);
    }

    /**
     * Create a static data source from a CSV rating file.
     * @param file The CSV rating file.
     * @return The data source.
     */
    public static StaticDataSource csvRatingFile(Path file) {
        StaticDataSource src = new StaticDataSource();
        TextEntitySource text = new TextEntitySource(LKFileUtils.basename(file.toString(), false));
        text.setFormat(Formats.csvRatings());
        text.setFile(file);
        src.addSource(text);
        return src;
    }

    /**
     * Create a static data source from a list/collection.
     * @param entities The collection of entities.
     * @return The static data source.
     */
    public static StaticDataSource fromList(Collection<? extends Entity> entities) {
        StaticDataSource source = new StaticDataSource("memory");
        source.addSource(entities);
        return source;
    }
}
