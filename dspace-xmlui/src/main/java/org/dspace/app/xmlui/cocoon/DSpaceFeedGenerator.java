/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.xmlui.cocoon;

import com.sun.syndication.io.FeedException;
import java.io.IOException;
import java.io.Serializable;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.avalon.excalibur.pool.Recyclable;
import org.apache.avalon.framework.configuration.Configurable;
import org.apache.avalon.framework.configuration.Configuration;
import org.apache.avalon.framework.configuration.ConfigurationException;
import org.apache.avalon.framework.parameters.Parameters;
import org.apache.cocoon.ProcessingException;
import org.apache.cocoon.ResourceNotFoundException;
import org.apache.cocoon.caching.CacheableProcessingComponent;
import org.apache.cocoon.environment.ObjectModelHelper;
import org.apache.cocoon.environment.Request;
import org.apache.cocoon.environment.SourceResolver;
import org.apache.cocoon.generation.AbstractGenerator;
import org.apache.cocoon.util.HashUtil;
import org.apache.cocoon.xml.dom.DOMStreamer;
import org.apache.excalibur.source.SourceValidity;
import org.apache.log4j.Logger;
import org.dspace.app.util.SyndicationFeed;
import org.dspace.app.xmlui.aspect.discovery.DiscoveryUIUtils;
import org.dspace.app.xmlui.aspect.discovery.SimpleSearch;
import static org.dspace.app.xmlui.cocoon.AbstractDSpaceTransformer.decodeFromURL;
import org.dspace.app.xmlui.utils.ContextUtil;
import org.dspace.app.xmlui.utils.DSpaceValidity;
import org.dspace.app.xmlui.utils.FeedUtils;
import org.dspace.authorize.AuthorizeManager;
import org.dspace.browse.BrowseEngine;
import org.dspace.browse.BrowseException;
import org.dspace.browse.BrowseIndex;
import org.dspace.browse.BrowseInfo;
import org.dspace.browse.BrowseItem;
import org.dspace.browse.BrowserScope;
import org.dspace.content.Collection;
import org.dspace.content.Community;
import org.dspace.content.DSpaceObject;
import org.dspace.content.Item;
import org.dspace.core.ConfigurationManager;
import org.dspace.core.Constants;
import org.dspace.core.Context;
import org.dspace.discovery.DiscoverHitHighlightingField;
import org.dspace.discovery.DiscoverQuery;
import org.dspace.discovery.DiscoverResult;
import org.dspace.discovery.SearchUtils;
import org.dspace.discovery.configuration.DiscoveryConfiguration;
import org.dspace.discovery.configuration.DiscoveryHitHighlightFieldConfiguration;
import org.dspace.discovery.configuration.DiscoverySortConfiguration;
import org.dspace.discovery.configuration.DiscoverySortFieldConfiguration;
import org.dspace.eperson.Group;
import org.dspace.handle.HandleManager;
import org.dspace.sort.SortException;
import org.dspace.sort.SortOption;
import org.w3c.dom.Document;
import org.xml.sax.SAXException;

/**
 *
 * Generate a syndication feed for DSpace, either a community or collection
 * or the whole repository. This code was adapted from the syndication found
 * in DSpace's JSP implementation, "org.dspace.app.webui.servlet.FeedServlet".
 *
 * One thing that has been modified from DSpace's JSP implementation is what
 * is placed inside an item's description, we've changed it so that the list
 * of metadata fields is scanned until a value is found and the first one
 * found is used as the description. This means that we look at the abstract,
 * description, alternative title, title, etc., too and the first metadata
 * value found is used.
 *
 * I18N: Feeds are internationalized, meaning that they may contain references
 * to messages contained in the global messages.xml file using cocoon's i18n
 * schema. However, the library I used to build the feeds does not understand
 * this schema to work around this limitation, so I created a little hack. It
 * basically works like this, when text that needs to be localized is put into
 * the feed it is always mangled such that a prefix is added to the messages's
 * key. Thus if the key were "xmlui.feed.text" then the resulting text placed
 * into the feed would be "I18N:xmlui.feed.text". After the library is finished
 * and produced, its final result the output is traversed to find these
 * occurrences ande replace them with proper cocoon i18n elements.
 *
 *
 *
 * @author Scott Phillips, Ben Bosman, Richard Rodgers
 */

public class DSpaceFeedGenerator extends AbstractGenerator
                implements Configurable, CacheableProcessingComponent, Recyclable
{
    private static final Logger log = Logger.getLogger(DSpaceFeedGenerator.class);

    /** The feed's requested format */
    private String format = null;
    
    /** The feed's scope, null if no scope */
    private String handle = null;
    
    /** number of DSpace items per feed */
    private static final int ITEM_COUNT = ConfigurationManager.getIntProperty("webui.feed.items");
    
    /**
     * How long should RSS feed cache entries be valid? milliseconds * seconds *
     * minutes * hours default to 24 hours if config parameter is not present or
     * wrong
     */
    private static final long CACHE_AGE;
    static
    {
        final String ageCfgName = "webui.feed.cache.age";
        final long ageCfg = ConfigurationManager.getIntProperty(ageCfgName, 24);
        CACHE_AGE = 1 * 6 * 6 * ageCfg;
    }
    
    /** configuration option to include Item which does not have READ by Anonymous enabled **/
    private static boolean includeRestrictedItems = ConfigurationManager.getBooleanProperty("harvest.includerestricted.rss", true);


    /** Cache of this object's validitity */
    private DSpaceValidity validity = null;
    
    /** The cache of recently submitted items */
    private Item recentSubmissionItems[];
    
    /**
     * Generate the unique caching key.
     * This key must be unique inside the space of this component.
     */
    public Serializable getKey()
    {
        if(this.handle.contains("/discover"))
        {
            this.handle += "/" + ObjectModelHelper.getRequest(objectModel).getQueryString();
        }
        String key = "key:" + this.handle + ":" + this.format;
        return HashUtil.hash(key);
    }

    /**
     * Generate the cache validity object.
     *
     * The validity object will include the collection being viewed and
     * all recently submitted items. This does not include the community / collection
     * hierarchy, when this changes they will not be reflected in the cache.
     */
    public SourceValidity getValidity()
    {
        if (this.validity == null)
        {
            try
            {
                DSpaceValidity validity = new FeedValidity();
                
                Context context = ContextUtil.obtainContext(objectModel);

                DSpaceObject dso = null;
                
                if (handle != null && !handle.contains("site"))
                {
                    dso = HandleManager.resolveToObject(context, handle);
                }
                
                validity.add(dso);
                
                // add recently submitted items
                if(this.handle.contains("/discover"))
                {
                    for(Item item : getRecentlySearchedItems(context,dso))
                    {
                        validity.add(item);
                    }
                }
                else
                {
                    for(Item item : getRecentlySubmittedItems(context,dso))
                    {
                        validity.add(item);
                    }
                }
                
                this.validity = validity.complete();
            }
            catch (RuntimeException e)
            {
                throw e;
            }
            catch (Exception e)
            {
                return null;
            }
        }
        return this.validity;
    }
    
    
    
    /**
     * Setup component wide configuration
     */
    public void configure(Configuration conf) throws ConfigurationException
    {
    }
    
    
    /**
     * Setup configuration for this request
     */
    public void setup(SourceResolver resolver, Map objectModel, String src,
            Parameters par) throws ProcessingException, SAXException,
            IOException
    {
        super.setup(resolver, objectModel, src, par);
        
        this.format = par.getParameter("feedFormat", null);
        this.handle = par.getParameter("handle",null);
    }
    
    
    /**
     * Generate the syndication feed.
     */
    public void generate() throws IOException, SAXException, ProcessingException
    {
        try
        {
            Context context = ContextUtil.obtainContext(objectModel);
            DSpaceObject dso = null;
            
            String url = ObjectModelHelper.getRequest(objectModel).getRequestURI();
        //    System.out.print("\n ==== "+url+"  ===\n");
            
            if (handle != null && !handle.contains("site"))
            {
                dso = HandleManager.resolveToObject(context, handle);
                if (dso == null)
                {
                    // If we were unable to find a handle then return page not found.
                    throw new ResourceNotFoundException("Unable to find DSpace object matching the given handle: "+handle);
                }
                
                if (!(dso.getType() == Constants.COLLECTION || dso.getType() == Constants.COMMUNITY))
                {
                    // The handle is valid but the object is not a container.
                    throw new ResourceNotFoundException("Unable to syndicate DSpace object: "+handle);
                }
            }
        
            SyndicationFeed feed = new SyndicationFeed(SyndicationFeed.UITYPE_XMLUI);
            if(!url.contains("/discover"))
                feed.populate(ObjectModelHelper.getRequest(objectModel), dso, getRecentlySubmittedItems(context,dso), FeedUtils.i18nLabels);
            else
                feed.populate(ObjectModelHelper.getRequest(objectModel), dso, getRecentlySearchedItems(context,dso), FeedUtils.i18nLabels);
            feed.setType(this.format);
            Document dom = feed.outputW3CDom();
            FeedUtils.unmangleI18N(dom);
            DOMStreamer streamer = new DOMStreamer(contentHandler, lexicalHandler);
            streamer.stream(dom);
        }
        catch (IllegalArgumentException iae)
        {
                throw new ResourceNotFoundException("Syndication feed format, '"+this.format+"', is not supported.", iae);
        }
        catch (FeedException fe)
        {
                throw new SAXException(fe);
        }
        catch (SQLException sqle)
        {
                throw new SAXException(sqle);
        }
    }
    
    /**
     * @return recently submitted Items within the indicated scope
     */
    @SuppressWarnings("unchecked")
    private Item[] getRecentlySubmittedItems(Context context, DSpaceObject dso)
            throws SQLException
    {
        String source = ConfigurationManager.getProperty("recent.submissions.sort-option");
        BrowserScope scope = new BrowserScope(context);
        if (dso instanceof Collection)
        {
            scope.setCollection((Collection) dso);
        }
        else if (dso instanceof Community)
        {
            scope.setCommunity((Community) dso);
        }
        scope.setResultsPerPage(ITEM_COUNT);

        // FIXME Exception handling
        try
        {
            scope.setBrowseIndex(BrowseIndex.getItemBrowseIndex());
            for (SortOption so : SortOption.getSortOptions())
            {
                if (so.getName().equals(source))
                {
                    scope.setSortBy(so.getNumber());
                    scope.setOrder(SortOption.DESCENDING);
                }
            }

            BrowseEngine be = new BrowseEngine(context);
            BrowseInfo results = be.browseMini(scope);
            this.recentSubmissionItems = results.getItemResults(context);

            // filter out Items that are not world-readable
            if (!includeRestrictedItems)
            {
                List<Item> result = new ArrayList<Item>();
                for (Item item : this.recentSubmissionItems)
                {
                checkAccess:
                    for (Group group : AuthorizeManager.getAuthorizedGroups(context, item, Constants.READ))
                    {
                        if ((group.getID() == Group.ANONYMOUS_ID))
                        {
                            result.add(item);
                            break checkAccess;
                        }
                    }
                }
                this.recentSubmissionItems = result.toArray(new Item[result.size()]);
            }
        }
        catch (BrowseException bex)
        {
            log.error("Caught browse exception", bex);
        }
        catch (SortException e)
        {
            log.error("Caught sort exception", e);
        }
        return this.recentSubmissionItems;
    }
    
    /**
     * @return recently submitted Items within the indicated scope
     */
    @SuppressWarnings("unchecked")
    private Item[] getRecentlySearchedItems(Context context, DSpaceObject dso) throws SQLException
    {
        try{
            Request request = ObjectModelHelper.getRequest(objectModel);
            
            // *** add the filtered queries into the DiscoverQuery object *** //
            List<String> filterQueries = new ArrayList<String>();
            String url = request.getRequestURI();
            SimpleSearch ss = new SimpleSearch();
            ss.setup(null, objectModel, url, parameters);
            String[] fqs = DiscoveryUIUtils.getFilterQueries(request, context);
            if (fqs != null)
            {
                filterQueries.addAll(Arrays.asList(fqs));
            }
            
            DiscoverQuery queryArgs = new DiscoverQuery();
            
            //Add the configured default filter queries
            DiscoveryConfiguration discoveryConfiguration = SearchUtils.getDiscoveryConfiguration(dso);
            List<String> defaultFilterQueries = discoveryConfiguration.getDefaultFilterQueries();
            queryArgs.addFilterQueries(defaultFilterQueries.toArray(new String[defaultFilterQueries.size()]));

            if (filterQueries.size() > 0) {
                queryArgs.addFilterQueries(filterQueries.toArray(new String[filterQueries.size()]));
            }
            
            // *** get the page information *** //
            int page = 1;
            try {
                 page = Integer.parseInt(request.getParameter("page"));
            }
            catch (Exception e) {
                page = 1;
            }
            
            if (page > 1)
            {
                queryArgs.setStart((page - 1) * queryArgs.getMaxResults());
            }
            else
            {
                queryArgs.setStart(0);
            }
            
            Map<String, String> parameters = new HashMap<String, String>();
            parameters.put("page", "{pageNum}");
            System.out.print(parameters.toString());
       
            // *** set the max results to display *** //
            queryArgs.setMaxResults(Integer.parseInt(ObjectModelHelper.getRequest(objectModel).getParameter("rpp")));

            // *** set the sort by *** //
            String sortBy = ObjectModelHelper.getRequest(objectModel).getParameter("sort_by");
            DiscoverySortConfiguration searchSortConfiguration = discoveryConfiguration.getSearchSortConfiguration();
            if(sortBy == null){
                //Attempt to find the default one, if none found we use SCORE
                sortBy = "score";
                if(searchSortConfiguration != null){
                    for (DiscoverySortFieldConfiguration sortFieldConfiguration : searchSortConfiguration.getSortFields()) {
                        if(sortFieldConfiguration.equals(searchSortConfiguration.getDefaultSort())){
                            sortBy = SearchUtils.getSearchService().toSortFieldIndex(sortFieldConfiguration.getMetadataField(), sortFieldConfiguration.getType());
                        }
                    }
                }
            }
            
            // *** set the sort order *** //
            String sortOrder = ObjectModelHelper.getRequest(objectModel).getParameter("order");
            if(sortOrder == null && searchSortConfiguration != null){
                sortOrder = searchSortConfiguration.getDefaultSortOrder().toString();
            }

            if (sortOrder == null || sortOrder.equalsIgnoreCase("DESC"))
            {
                queryArgs.setSortField(sortBy, DiscoverQuery.SORT_ORDER.desc);
            }
            else
            {
                queryArgs.setSortField(sortBy, DiscoverQuery.SORT_ORDER.asc);
            }

            // *** set the group by *** //
            String groupBy = ObjectModelHelper.getRequest(objectModel).getParameter("group_by");
            if (groupBy != null && !groupBy.equalsIgnoreCase("none")) {
                // Construct a Collapse Field Query 
                queryArgs.addProperty("collapse.field", groupBy);
                queryArgs.addProperty("collapse.threshold", "1");
                queryArgs.addProperty("collapse.includeCollapsedDocs.fl", "handle");
                queryArgs.addProperty("collapse.facet", "before");

                queryArgs.setSortField("dc.type", DiscoverQuery.SORT_ORDER.asc);

            }
            
            String query = decodeFromURL(request.getParameter("query"));

            queryArgs.setQuery(query != null && !query.trim().equals("") ? query : null);
            

            if(discoveryConfiguration.getHitHighlightingConfiguration() != null)
            {
                List<DiscoveryHitHighlightFieldConfiguration> metadataFields = discoveryConfiguration.getHitHighlightingConfiguration().getMetadataFields();
                for (DiscoveryHitHighlightFieldConfiguration fieldConfiguration : metadataFields)
                {
                    queryArgs.addHitHighlightingField(new DiscoverHitHighlightingField(fieldConfiguration.getField(), fieldConfiguration.getMaxSize(), fieldConfiguration.getSnippets()));
                }
            }

            queryArgs.setSpellCheck(discoveryConfiguration.isSpellCheckEnabled());

            DiscoverResult queryResults = SearchUtils.getSearchService().search(context, dso, queryArgs);
            
            List<BrowseItem> bitems = new ArrayList<BrowseItem>();
            int itemCount = 0;
            for (DSpaceObject solrDoc : queryResults.getDspaceObjects())
            {
                if(itemCount < ITEM_COUNT)
                {
                    Item item = (Item) solrDoc;
                    BrowseItem bitem = new BrowseItem(context, item.getID(),
                            item.isArchived(), item.isWithdrawn(), item.isDiscoverable());
                    bitems.add(bitem);
                }
                itemCount++;
            }
            
            BrowserScope scope = new BrowserScope(context);
            if (dso instanceof Collection)
            {
                scope.setCollection((Collection) dso);
            }
            else if (dso instanceof Community)
            {
                scope.setCommunity((Community) dso);
            }
            scope.setResultsPerPage(ITEM_COUNT);
            
            scope.setBrowseIndex(BrowseIndex.getItemBrowseIndex());
            for (SortOption so : SortOption.getSortOptions())
            {
                if (so.getName().equals(source))
                {
                    scope.setSortBy(so.getNumber());
                    scope.setOrder(SortOption.DESCENDING);
                }
            }
            
            BrowseEngine be = new BrowseEngine(context);
            BrowseInfo results = be.browseMiniWithResults(scope, bitems);
            this.recentSubmissionItems = results.getItemResults(context);

            // filter out Items that are not world-readable
            if (!includeRestrictedItems)
            {
                List<Item> result = new ArrayList<Item>();
                for (Item item : this.recentSubmissionItems)
                {
                checkAccess:
                    for (Group group : AuthorizeManager.getAuthorizedGroups(context, item, Constants.READ))
                    {
                        if (group.getID() == Group.ANONYMOUS_ID)
                        {
                            result.add(item);
                            break checkAccess;
                        }
                    }
                }
                this.recentSubmissionItems = result.toArray(new Item[result.size()]);
            }
        }
        catch (BrowseException bex)
        {
            log.error("Caught browse exception", bex);
        }
        catch (SortException e)
        {
            log.error("Caught sort exception", e);
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
        
        return this.recentSubmissionItems;
    }
    
    /**
     * Recycle
     */
    
    public void recycle()
    {
        this.format = null;
        this.handle = null;
        this.validity = null;
        this.recentSubmissionItems = null;
        super.recycle();
    }
    
    /**
     * Extend the standard DSpaceValidity object to support assumed
     * caching. Since feeds will constantly be requested we want to
     * assume that a feed is still valid instead of checking it
     * against the database anew everytime.
     *
     * This validity object will assume that a cache is still valid,
     * without rechecking it, for 24 hours.
     *
     */
    private static class FeedValidity extends DSpaceValidity
    {
        private static final long serialVersionUID = 1L;
                        
        /** When the cache's validity expires */
        private long expires = 0;
        
        /**
         * When the validity is completed record a timestamp to check later.
         */
        public DSpaceValidity complete()
        {
                this.expires = System.currentTimeMillis() + CACHE_AGE;
                
                return super.complete();
        }
        
        
        /**
         * Determine if the cache is still valid
         */
        public int isValid()
        {
            // Return true if we have a hash.
            if (this.completed)
            {
                if (System.currentTimeMillis() < this.expires)
                {
                        // If the cache hasn't expired the just assume that it is still valid.
                        return SourceValidity.VALID;
                }
                else
                {
                        // The cache is past its age
                        return SourceValidity.UNKNOWN;
                }
            }
            else
            {
                // This is an error state. We are being asked whether we are valid before
                // we have been initialized.
                return SourceValidity.INVALID;
            }
        }

        /**
         * Determine if the cache is still valid based
         * upon the other validity object.
         *
         * @param other
         *          The other validity object.
         */
        public int isValid(SourceValidity otherValidity)
        {
            if (this.completed && otherValidity instanceof FeedValidity)
            {
                FeedValidity other = (FeedValidity) otherValidity;
                if (hash == other.hash)
                {
                    // Update expiration time of both caches.
                    this.expires = System.currentTimeMillis() + CACHE_AGE;
                    other.expires = System.currentTimeMillis() + CACHE_AGE;

                    return SourceValidity.VALID;
                }
            }

            return SourceValidity.INVALID;
        }

    }
}
