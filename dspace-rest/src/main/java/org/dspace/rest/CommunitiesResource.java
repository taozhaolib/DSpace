/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.rest;

import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.apache.log4j.Logger;
import org.dspace.authorize.AuthorizeException;
import org.dspace.authorize.AuthorizeManager;
import org.dspace.rest.common.Collection;
import org.dspace.rest.common.Community;
import org.dspace.rest.exceptions.ContextException;
import org.dspace.usage.UsageEvent;

/**
 * Class which provides CRUD methods over communities.
 * 
 * @author Rostislav Novak (Computing and Information Centre, CTU in Prague)
 * 
 */
@Path("/communities")
public class CommunitiesResource extends Resource
{
    private static Logger log = Logger.getLogger(CommunitiesResource.class);

    private static final boolean writeStatistics;
	
	static{
		writeStatistics=ConfigurationManager.getBooleanProperty("rest","stats",false);
	}

    /**
     * Return all collections of community.
     * 
     * @param communityId
     *            Id of community in DSpace.
     * @param expand
     *            String in which is what you want to add to returned instance
     *            of collection. Options are: "all", "parentCommunityList",
     *            "parentCommunity", "items", "license" and "logo". If you want
     *            to use multiple options, it must be separated by commas.
     * @param limit
     *            Maximum collection in array. Default value is 100.
     * @param offset
     *            Index from which will start array of collections. Default
     *            value is 0.
     * @param headers
     *            If you want to access to community under logged user into
     *            context. In headers must be set header "rest-dspace-token"
     *            with passed token from login method.
     * @return Return array of collections of community.
     * @throws WebApplicationException
     *             It can be caused by creating context or while was problem
     *             with reading community from database(SQLException).
     */
    @GET
    @Path("/")
    @Produces({MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML})
    public org.dspace.rest.common.Community[] list(@QueryParam("expand") String expand) {
        org.dspace.core.Context context = null;
        try {
            context = new org.dspace.core.Context();

            org.dspace.content.Community[] topCommunities = org.dspace.content.Community.findAllTop(context);
            ArrayList<org.dspace.rest.common.Community> communityArrayList = new ArrayList<org.dspace.rest.common.Community>();
            for(org.dspace.content.Community community : topCommunities) {
                if(AuthorizeManager.authorizeActionBoolean(context, community, org.dspace.core.Constants.READ)) {
                    //Only list communities that this user has access to.
                    org.dspace.rest.common.Community restCommunity = new org.dspace.rest.common.Community(community, expand, context);
                    communityArrayList.add(restCommunity);
                }
            }

            context.complete();
        }
        catch (SQLException e)
        {
            processException("Could not read community(id=" + communityId + ") collections, SQLException. Message:" + e, context);
        }
        catch (ContextException e)
        {
            processException(
                    "Could not read community(id=" + communityId + ") collections, ContextException. Message:" + e.getMessage(),
                    context);
        }
        finally
        {
            processFinally(context);
        }

        } catch (SQLException e) {
            log.error(e.getMessage());
            throw new WebApplicationException(Response.Status.INTERNAL_SERVER_ERROR);
        } finally {
            if(context != null) {
                try {
                    context.complete();
                } catch (SQLException e) {
                    log.error(e.getMessage() + " occurred while trying to close");
                }
            }
        }
    }

    /**
     * Return all subcommunities of community.
     * 
     * @param communityId
     *            Id of community in DSpace.
     * @param expand
     *            String in which is what you want to add to returned instance
     *            of community. Options are: "all", "parentCommunity",
     *            "collections", "subCommunities" and "logo". If you want to use
     *            multiple options, it must be separated by commas.
     * @param limit
     *            Maximum communities in array. Default value is 20.
     * @param offset
     *            Index from which will start array of communities. Default
     *            value is 0.
     * @param headers
     *            If you want to access to community under logged user into
     *            context. In headers must be set header "rest-dspace-token"
     *            with passed token from login method.
     * @return Return array of subcommunities of community.
     * @throws WebApplicationException
     *             It can be caused by creating context or while was problem
     *             with reading community from database(SQLException).
     */
    @GET
    @Path("/{community_id}/communities")
    @Produces({ MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML })
    public Community[] getCommunityCommunities(@PathParam("community_id") Integer communityId,
            @QueryParam("expand") String expand, @QueryParam("limit") @DefaultValue("20") Integer limit,
            @QueryParam("offset") @DefaultValue("0") Integer offset, @QueryParam("userIP") String user_ip,
            @QueryParam("userAgent") String user_agent, @QueryParam("xforwarderfor") String xforwarderfor,
            @Context HttpHeaders headers, @Context HttpServletRequest request) throws WebApplicationException
    {

        log.info("Reading community(id=" + communityId + ") subcommunities.");
        org.dspace.core.Context context = null;
        ArrayList<Community> communities = null;

        try
        {
            context = createContext(getUser(headers));

            org.dspace.content.Community dspaceCommunity = findCommunity(context, communityId, org.dspace.core.Constants.READ);
            writeStats(dspaceCommunity, UsageEvent.Action.VIEW, user_ip, user_agent, xforwarderfor, headers,
                    request, context);

            if (!((limit != null) && (limit >= 0) && (offset != null) && (offset >= 0)))
            {
                log.warn("Pagging was badly set, using default values.");
                limit = 100;
                offset = 0;
            }

            communities = new ArrayList<Community>();
            org.dspace.content.Community[] dspaceCommunities = dspaceCommunity.getSubcommunities();
            for (int i = offset; (i < (offset + limit)) && (i < dspaceCommunities.length); i++)
            {
                if (AuthorizeManager.authorizeActionBoolean(context, dspaceCommunities[i], org.dspace.core.Constants.READ))
                {
                    communities.add(new Community(dspaceCommunities[i], expand, context));
                    writeStats(dspaceCommunities[i], UsageEvent.Action.VIEW, user_ip, user_agent,
                            xforwarderfor, headers, request, context);
                }
            }

            context.complete();
        }
        catch (SQLException e)
        {
            processException("Could not read community(id=" + communityId + ") subcommunities, SQLException. Message:" + e,
                    context);
        }
        catch (ContextException e)
        {
            processException(
                    "Could not read community(id=" + communityId + ") subcommunities, ContextException. Message:"
                            + e.getMessage(), context);
        }
        finally
        {
            processFinally(context);
        }

        log.trace("Community(id=" + communityId + ") subcommunities were successfully read.");
        return communities.toArray(new Community[0]);
    }

    /**
     * Create community at top level. Creating community at top level has
     * permission only admin.
     * 
     * @param community
     *            Community which will be created at top level of communities.
     * @param headers
     *            If you want to access to community under logged user into
     *            context. In headers must be set header "rest-dspace-token"
     *            with passed token from login method.
     * @return Returns response with handle of community, if was all ok.
     * @throws WebApplicationException
     *             It can be thrown by SQLException, AuthorizeException and
     *             ContextException.
     */
    @POST
    @Consumes({ MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML })
    public Community createCommunity(Community community, @QueryParam("userIP") String user_ip,
            @QueryParam("userAgent") String user_agent, @QueryParam("xforwarderfor") String xforwarderfor,
            @Context HttpHeaders headers, @Context HttpServletRequest request) throws WebApplicationException
    {

        log.info("Creating community at top level.");
        org.dspace.core.Context context = null;
        Community retCommunity = null;

        try
        {
            context = createContext(getUser(headers));

            if (!AuthorizeManager.isAdmin(context))
            {
                context.abort();
                String user = "anonymous";
                if (context.getCurrentUser() != null)
                {
                    user = context.getCurrentUser().getEmail();
                }
                log.error("User(" + user + ") has not permission to create community!");
                throw new WebApplicationException(Response.Status.UNAUTHORIZED);
            }

            org.dspace.content.Community dspaceCommunity = org.dspace.content.Community.create(null, context);
            writeStats(dspaceCommunity, UsageEvent.Action.CREATE, user_ip, user_agent, xforwarderfor,
                    headers, request, context);

            dspaceCommunity.setMetadata("name", community.getName());
            dspaceCommunity.setMetadata(org.dspace.content.Community.COPYRIGHT_TEXT, community.getCopyrightText());
            dspaceCommunity.setMetadata(org.dspace.content.Community.INTRODUCTORY_TEXT, community.getIntroductoryText());
            dspaceCommunity.setMetadata(org.dspace.content.Community.SHORT_DESCRIPTION, community.getShortDescription());
            dspaceCommunity.setMetadata(org.dspace.content.Community.SIDEBAR_TEXT, community.getSidebarText());
            dspaceCommunity.update();

            retCommunity = new Community(dspaceCommunity, "", context);
            context.complete();

        }
        catch (SQLException e)
        {
            processException("Could not create new top community, SQLException. Message: " + e, context);
        }
        catch (ContextException e)
        {
            processException("Could not create new top community, ContextException. Message: " + e.getMessage(), context);
        }
        catch (AuthorizeException e)
        {
            processException("Could not create new top community, AuthorizeException. Message: " + e.getMessage(), context);
        }
        finally
        {
            processFinally(context);
        }


        log.info("Community at top level has been successfully created. Handle:" + retCommunity.getHandle());
        return retCommunity;
    }

    /**
     * Create collection in community.
     * 
     * @param communityId
     *            Id of community in DSpace.
     * @param collection
     *            Collection which will be added into community.
     * @param headers
     *            If you want to access to community under logged user into
     *            context. In headers must be set header "rest-dspace-token"
     *            with passed token from login method.
     * @return Return response 200 if was everything all right. Otherwise 400
     *         when id of community was incorrect or 401 if was problem with
     *         permission to write into collection.
     * @throws WebApplicationException
     *             It is thrown when was problem with database reading or
     *             writing. Or problem with authorization to community. Or
     *             problem with creating context.
     */
    @POST
    @Path("/{community_id}/collections")
    @Consumes({ MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML })
    public Collection addCommunityCollection(@PathParam("community_id") Integer communityId, Collection collection,
            @QueryParam("userIP") String user_ip, @QueryParam("userAgent") String user_agent,
            @QueryParam("xforwarderfor") String xforwarderfor, @Context HttpHeaders headers, @Context HttpServletRequest request)
            throws WebApplicationException
    {

        log.info("Adding collection into community(id=" + communityId + ").");
        org.dspace.core.Context context = null;
        Collection retCollection = null;

        try
        {
            context = createContext(getUser(headers));
            org.dspace.content.Community dspaceCommunity = findCommunity(context, communityId, org.dspace.core.Constants.WRITE);

            writeStats(dspaceCommunity, UsageEvent.Action.UPDATE, user_ip, user_agent, xforwarderfor,
                    headers, request, context);

            org.dspace.content.Collection dspaceCollection = dspaceCommunity.createCollection();
            dspaceCollection.setLicense(collection.getLicense());
            // dspaceCollection.setLogo(collection.getLogo()); // TODO Add this option.
            dspaceCollection.setMetadata("name", collection.getName());
            dspaceCollection.setMetadata(org.dspace.content.Collection.COPYRIGHT_TEXT, collection.getCopyrightText());
            dspaceCollection.setMetadata(org.dspace.content.Collection.INTRODUCTORY_TEXT, collection.getIntroductoryText());
            dspaceCollection.setMetadata(org.dspace.content.Collection.SHORT_DESCRIPTION, collection.getShortDescription());
            dspaceCollection.setMetadata(org.dspace.content.Collection.SIDEBAR_TEXT, collection.getSidebarText());
            dspaceCollection.setLicense(collection.getLicense());
            dspaceCollection.update();
            dspaceCommunity.update();

            retCollection = new Collection(dspaceCollection, "", context, 100, 0);
            context.complete();

        }
        catch (SQLException e)
        {
            processException("Could not add collection into community(id=" + communityId + "), SQLException. Message:" + e,
                    context);
        }
        catch (AuthorizeException e)
        {
            processException("Could not add collection into community(id=" + communityId + "), AuthorizeException. Message:" + e,
                    context);
        }
        catch (ContextException e)
        {
            processException(
                    "Could not add collection into community(id=" + communityId + "), ContextException. Message:"
                            + e.getMessage(), context);
        }
        finally
        {
            processFinally(context);
        }


        log.info("Collection was successfully added into community(id=" + communityId + "). Collection handle="
                + retCollection.getHandle());
        return retCollection;
    }

    /**
     * Create subcommunity in community.
     * 
     * @param communityId
     *            Id of community in DSpace, in which will be created
     *            subcommunity.
     * @param community
     *            Community which will be added into community.
     * @param headers
     *            If you want to access to community under logged user into
     *            context. In headers must be set header "rest-dspace-token"
     *            with passed token from login method.
     * @return Return response 200 if was everything all right. Otherwise 400
     *         when id of community was incorrect or 401 if was problem with
     *         permission to write into collection.
     * @throws WebApplicationException
     *             It is thrown when was problem with database reading or
     *             writing. Or problem with authorization to community. Or
     *             problem with creating context.
     */
    @POST
    @Path("/{community_id}/communities")
    @Consumes({ MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML })
    public Community addCommunityCommunity(@PathParam("community_id") Integer communityId, Community community,
            @QueryParam("userIP") String user_ip, @QueryParam("userAgent") String user_agent,
            @QueryParam("xforwarderfor") String xforwarderfor, @Context HttpHeaders headers, @Context HttpServletRequest request)
            throws WebApplicationException
    {

        log.info("Add subcommunity into community(id=" + communityId + ").");
        org.dspace.core.Context context = null;
        Community retCommunity = null;

        try
        {
            context = createContext(getUser(headers));
            org.dspace.content.Community dspaceParentCommunity = findCommunity(context, communityId,
                    org.dspace.core.Constants.WRITE);

            writeStats(dspaceParentCommunity, UsageEvent.Action.UPDATE, user_ip, user_agent, xforwarderfor,
                    headers, request, context);

            org.dspace.content.Community dspaceCommunity = org.dspace.content.Community.create(dspaceParentCommunity, context);
            dspaceCommunity.setMetadata("name", community.getName());
            dspaceCommunity.setMetadata(org.dspace.content.Community.COPYRIGHT_TEXT, community.getCopyrightText());
            dspaceCommunity.setMetadata(org.dspace.content.Community.INTRODUCTORY_TEXT, community.getIntroductoryText());
            dspaceCommunity.setMetadata(org.dspace.content.Community.SHORT_DESCRIPTION, community.getShortDescription());
            dspaceCommunity.setMetadata(org.dspace.content.Community.SIDEBAR_TEXT, community.getSidebarText());
            dspaceCommunity.update();
            dspaceParentCommunity.update();

            retCommunity = new Community(dspaceCommunity, "", context);
            context.complete();

        }
        catch (SQLException e)
        {
            processException("Could not add subcommunity into community(id=" + communityId + "), SQLException. Message:" + e,
                    context);
        }
        catch (AuthorizeException e)
        {
            processException("Could not add subcommunity into community(id=" + communityId + "), AuthorizeException. Message:"
                    + e, context);
        }
        catch (ContextException e)
        {
            processException("Could not add subcommunity into community(id=" + communityId + "), ContextException. Message:" + e,
                    context);
        }
        finally
        {
            processFinally(context);
        }


        log.info("Subcommunity was successfully added in community(id=" + communityId + ").");
        return retCommunity;
    }

    /**
     * Update community. Replace all information about community except: id,
     * handle and expandle items.
     * 
     * @param communityId
     *            Id of community in DSpace.
     * @param community
     *            Instance of community which will replace actual community in
     *            DSpace.
     * @param headers
     *            If you want to access to community under logged user into
     *            context. In headers must be set header "rest-dspace-token"
     *            with passed token from login method.
     * @return Response 200 if was all ok. Otherwise 400 if was id incorrect or
     *         401 if logged user has no permission to delete community.
     * @throws WebApplicationException
     *             It is throw when was problem with creating context or problem
     *             with database reading or writing. Or problem with writing to
     *             community caused by authorization.
     */
    @PUT
    @Path("/{community_id}")
    @Produces({MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML})
    public org.dspace.rest.common.Community getCommunity(@PathParam("community_id") Integer community_id, @QueryParam("expand") String expand,
    		@QueryParam("userIP") String user_ip, @QueryParam("userAgent") String user_agent, @QueryParam("xforwarderfor") String xforwarderfor,
    		@Context HttpHeaders headers, @Context HttpServletRequest request) {
        org.dspace.core.Context context = null;
        try {
            context = new org.dspace.core.Context();

            org.dspace.content.Community community = org.dspace.content.Community.find(context, community_id);
            if(AuthorizeManager.authorizeActionBoolean(context, community, org.dspace.core.Constants.READ)) {
            	if(writeStatistics){
    				writeStats(context, community_id, user_ip, user_agent, xforwarderfor, headers, request);
    			}
                return new org.dspace.rest.common.Community(community, expand, context);
            } else {
                throw new WebApplicationException(Response.Status.UNAUTHORIZED);
            }
        } catch (SQLException e) {
            log.error(e.getMessage());
            throw new WebApplicationException(Response.Status.INTERNAL_SERVER_ERROR);
        } finally {
            if(context != null) {
                try {
                    context.complete();
                } catch (SQLException e) {
                    log.error(e.getMessage() + " occurred while trying to close");
                }
            }
        }
    }
    
    private void writeStats(org.dspace.core.Context context, Integer community_id, String user_ip, String user_agent,
 			String xforwarderfor, HttpHeaders headers,
 			HttpServletRequest request) {
 		
     	try{
     		DSpaceObject community = DSpaceObject.find(context, Constants.COMMUNITY, community_id);
     		
     		if(user_ip==null || user_ip.length()==0){
     			new DSpace().getEventService().fireEvent(
 	                     new UsageEvent(
 	                                     UsageEvent.Action.VIEW,
 	                                     request,
 	                                     context,
 	                                    community));
     		} else{
 	    		new DSpace().getEventService().fireEvent(
 	                     new UsageEvent(
 	                                     UsageEvent.Action.VIEW,
 	                                     user_ip,
 	                                     user_agent,
 	                                     xforwarderfor,
 	                                     context,
 	                                    community));
     		}
     		log.debug("fired event");
     		
 		} catch(SQLException ex){
 			log.error("SQL exception can't write usageEvent \n" + ex);
 		}
     		
 	}
}
