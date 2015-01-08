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
import java.util.List;

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
import org.dspace.browse.BrowseException;
import org.dspace.content.service.ItemService;
import org.dspace.rest.common.Collection;
import org.dspace.rest.common.Item;
import org.dspace.rest.common.MetadataEntry;
import org.dspace.rest.exceptions.ContextException;
import org.dspace.usage.UsageEvent;

/**
 * This class provides all CRUD operation over collections.
 * 
 * @author Rostislav Novak (Computing and Information Centre, CTU in Prague)
 */
@Path("/collections")
public class CollectionsResource extends Resource
{
    private static Logger log = Logger.getLogger(CollectionsResource.class);

    @javax.ws.rs.core.Context ServletContext servletContext;
    
    private static final boolean writeStatistics;
	
	static{
		writeStatistics=ConfigurationManager.getBooleanProperty("rest","stats",false);
	}

    @GET
    @Path("/")
    @Produces({MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML})
    public org.dspace.rest.common.Collection[] list(@QueryParam("expand") String expand, @QueryParam("limit") @DefaultValue("100") Integer limit, @QueryParam("offset") @DefaultValue("0") Integer offset) {
        org.dspace.core.Context context = null;
        try {
            context = new org.dspace.core.Context();

            // Index item to browse.
            org.dspace.browse.IndexBrowse browse = new org.dspace.browse.IndexBrowse();
            browse.indexItem(dspaceItem);

            log.trace("Installing item to collection(id=" + collectionId + ").");
            dspaceItem = org.dspace.content.InstallItem.installItem(context, workspaceItem);

            returnItem = new Item(dspaceItem, "", context);

            context.complete();

        }
        catch (SQLException e)
        {
            processException("Could not add item into collection(id=" + collectionId + "), SQLException. Message: " + e, context);
        }
        catch (AuthorizeException e)
        {
            processException("Could not add item into collection(id=" + collectionId + "), AuthorizeException. Message: " + e,
                    context);
        }
        catch (IOException e)
        {
            processException("Could not add item into collection(id=" + collectionId + "), IOException. Message: " + e, context);
        }
        catch (BrowseException e)
        {
            processException("Could not add item into browse index, BrowseException. Message: " + e, context);
        }
        catch (ContextException e)
        {
            processException(
                    "Could not add item into collection(id=" + collectionId + "), ContextException. Message: " + e.getMessage(),
                    context);
        }
        finally
        {
            processFinally(context);
        }

        log.info("Item successfully created in collection(id=" + collectionId + "). Item handle=" + returnItem.getHandle());
        return returnItem;
    }

    /**
     * Update collection. It replace all properties.
     * 
     * @param collectionId
     *            Id of collection in DSpace.
     * @param collection
     *            Collection which will replace properties of actual collection.
     * @param headers
     *            If you want to access to collection under logged user into
     *            context. In headers must be set header "rest-dspace-token"
     *            with passed token from login method.
     * @return Return response 200 if was everything all right. Otherwise 400
     *         when id of community was incorrect or 401 if was problem with
     *         permission to write into collection.
     * @throws WebApplicationException
     *             It is thrown when was problem with database reading or
     *             writing. Or problem with authorization to collection. Or
     *             problem with creating context.
     */
    @PUT
    @Path("/{collection_id}")
    @Consumes({ MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML })
    public Response updateCollection(@PathParam("collection_id") Integer collectionId,
            org.dspace.rest.common.Collection collection, @QueryParam("userIP") String user_ip,
            @QueryParam("userAgent") String user_agent, @QueryParam("xforwarderfor") String xforwarderfor,
            @Context HttpHeaders headers, @Context HttpServletRequest request) throws WebApplicationException
    {

        log.info("Updating collection(id=" + collectionId + ").");
        org.dspace.core.Context context = null;

        try
        {
            context = createContext(getUser(headers));
            org.dspace.content.Collection dspaceCollection = findCollection(context, collectionId,
                    org.dspace.core.Constants.WRITE);

            writeStats(dspaceCollection, UsageEvent.Action.UPDATE, user_ip, user_agent, xforwarderfor,
                    headers, request, context);

            dspaceCollection.setMetadata("name", collection.getName());
            dspaceCollection.setLicense(collection.getLicense());
            // dspaceCollection.setLogo(collection.getLogo()); // TODO Add this option.
            dspaceCollection.setMetadata(org.dspace.content.Collection.COPYRIGHT_TEXT, collection.getCopyrightText());
            dspaceCollection.setMetadata(org.dspace.content.Collection.INTRODUCTORY_TEXT, collection.getIntroductoryText());
            dspaceCollection.setMetadata(org.dspace.content.Collection.SHORT_DESCRIPTION, collection.getShortDescription());
            dspaceCollection.setMetadata(org.dspace.content.Collection.SIDEBAR_TEXT, collection.getSidebarText());
            dspaceCollection.update();

            context.complete();

        }
        catch (ContextException e)
        {
            processException("Could not update collection(id=" + collectionId + "), ContextException. Message: " + e.getMessage(),
                    context);
        }
        catch (SQLException e)
        {
            processException("Could not update collection(id=" + collectionId + "), SQLException. Message: " + e, context);
        }
        catch (AuthorizeException e)
        {
            processException("Could not update collection(id=" + collectionId + "), AuthorizeException. Message: " + e, context);
        }
        finally
        {
            processFinally(context);
        }

        log.info("Collection(id=" + collectionId + ") successfully updated.");
        return Response.ok().build();
    }

    /**
     * Delete collection.
     * 
     * @param collectionId
     *            Id of collection which will be deleted.
     * @param headers
     *            If you want to access to collection under logged user into
     *            context. In headers must be set header "rest-dspace-token"
     *            with passed token from login method.
     * @return Return response code OK(200) if was everything all right.
     *         Otherwise return NOT_FOUND(404) if was id of community or
     *         collection incorrect. Or (UNAUTHORIZED)401 if was problem with
     *         permission to community or collection.
     * @throws WebApplicationException
     *             It is throw when was problem with creating context or problem
     *             with database reading or writing. Or problem with deleting
     *             collection caused by IOException or authorization.
     */
    @DELETE
    @Path("/{collection_id}")
    @Consumes({ MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML })
    public Response deleteCollection(@PathParam("collection_id") Integer collectionId, @QueryParam("userIP") String user_ip,
            @QueryParam("userAgent") String user_agent, @QueryParam("xforwarderfor") String xforwarderfor,
            @Context HttpHeaders headers, @Context HttpServletRequest request) throws WebApplicationException
    {

        log.info("Delete collection(id=" + collectionId + ").");
        org.dspace.core.Context context = null;

        try
        {
            context = createContext(getUser(headers));
            org.dspace.content.Collection dspaceCollection = findCollection(context, collectionId,
                    org.dspace.core.Constants.DELETE);

            writeStats(dspaceCollection, UsageEvent.Action.REMOVE, user_ip, user_agent, xforwarderfor,
                    headers, request, context);

            org.dspace.content.Community community = (org.dspace.content.Community) dspaceCollection.getParentObject();
            community.removeCollection(dspaceCollection);

            context.complete();

        }
        catch (ContextException e)
        {
            processException(
                    "Could not delete collection(id=" + collectionId + "), ContextExcpetion. Message: " + e.getMessage(), context);
        }
        catch (SQLException e)
        {
            processException("Could not delete collection(id=" + collectionId + "), SQLException. Message: " + e, context);
        }
        catch (AuthorizeException e)
        {
            processException("Could not delete collection(id=" + collectionId + "), AuthorizeException. Message: " + e, context);
        }
        catch (IOException e)
        {
            processException("Could not delete collection(id=" + collectionId + "), IOException. Message: " + e, context);
        }
        finally
        {
            processFinally(context);
        }

        log.info("Collection(id=" + collectionId + ") was successfully deleted.");
        return Response.ok().build();
    }

    /**
     * Delete item in collection.
     * 
     * @param collectionId
     *            Id of collection which will be deleted.
     * 
     * @param itemId
     *            Id of item in colletion.
     * @return It returns status code: OK(200). NOT_FOUND(404) if item or
     *         collection was not found, UNAUTHORIZED(401) if user is not
     *         allowed to delete item or permission to write into collection.
     * @throws WebApplicationException
     *             It can be thrown by: SQLException, when was problem with
     *             database reading or writting. AuthorizeException, when was
     *             problem with authorization to item or collection.
     *             IOException, when was problem with removing item.
     *             ContextException, when was problem with creating context of
     *             DSpace.
     */
    @DELETE
    @Path("/{collection_id}/items/{item_id}")
    @Consumes({ MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML })
    public Response deleteCollectionItem(@PathParam("collection_id") Integer collectionId, @PathParam("item_id") Integer itemId,
            @QueryParam("userIP") String user_ip, @QueryParam("userAgent") String user_agent,
            @QueryParam("xforwarderfor") String xforwarderfor, @Context HttpHeaders headers, @Context HttpServletRequest request)
            throws WebApplicationException
    {

        log.info("Delete item(id=" + itemId + ") in collection(id=" + collectionId + ").");
        org.dspace.core.Context context = null;

        try
        {
            context = createContext(getUser(headers));
            org.dspace.content.Collection dspaceCollection = findCollection(context, collectionId,
                    org.dspace.core.Constants.WRITE);

            org.dspace.content.Item item = null;
            org.dspace.content.ItemIterator dspaceItems = dspaceCollection.getItems();
            while (dspaceItems.hasNext())
            {
                org.dspace.content.Item dspaceItem = dspaceItems.next();
                if (dspaceItem.getID() == itemId)
                {
                    item = dspaceItem;
                }
            }

            if (item == null)
            {
                context.abort();
                log.warn("Item(id=" + itemId + ") was not found!");
                throw new WebApplicationException(Response.Status.NOT_FOUND);
            }
            else if (!AuthorizeManager.authorizeActionBoolean(context, item, org.dspace.core.Constants.REMOVE))
            {
                context.abort();
                if (context.getCurrentUser() != null)
                {
                    log.error("User(" + context.getCurrentUser().getEmail() + ") has not permission to delete item!");
                }
                else
                {
                    log.error("User(anonymous) has not permission to delete item!");
                }
                throw new WebApplicationException(Response.Status.UNAUTHORIZED);
            }

            writeStats(dspaceCollection, UsageEvent.Action.UPDATE, user_ip, user_agent, xforwarderfor,
                    headers, request, context);
            writeStats(item, UsageEvent.Action.REMOVE, user_ip, user_agent, xforwarderfor, headers, request, context);

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
        catch (AuthorizeException e)
        {
            processException("Could not delete item(id=" + itemId + ") in collection(id=" + collectionId
                    + "), AuthorizeException. Message: " + e, context);
        }
        catch (IOException e)
        {
            processException("Could not delete item(id=" + itemId + ") in collection(id=" + collectionId
                    + "), IOException. Message: " + e, context);
        }
        finally
        {
            processFinally(context);
        }

        log.info("Item(id=" + itemId + ") in collection(id=" + collectionId + ") was successfully deleted.");
        return Response.ok().build();
    }

    @GET
    @Path("/{collection_id}")
    @Produces({MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML})
    public org.dspace.rest.common.Collection getCollection(@PathParam("collection_id") Integer collection_id, @QueryParam("expand") String expand, 
    		@QueryParam("limit") @DefaultValue("100") Integer limit, @QueryParam("offset") @DefaultValue("0") Integer offset,
    		@QueryParam("userIP") String user_ip, @QueryParam("userAgent") String user_agent, @QueryParam("xforwarderfor") String xforwarderfor,
    		@Context HttpHeaders headers, @Context HttpServletRequest request) {
        org.dspace.core.Context context = null;
        try {
            context = new org.dspace.core.Context();

            org.dspace.content.Collection collection = org.dspace.content.Collection.find(context, collection_id);
            if(AuthorizeManager.authorizeActionBoolean(context, collection, org.dspace.core.Constants.READ)) {
            	if(writeStatistics){
    				writeStats(context, collection_id, user_ip, user_agent, xforwarderfor, headers, request);
    			}
                return new org.dspace.rest.common.Collection(collection, expand, context, limit, offset);
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
        return collection;
    }
    
    private void writeStats(org.dspace.core.Context context, Integer collection_id, String user_ip, String user_agent,
			String xforwarderfor, HttpHeaders headers,
			HttpServletRequest request) {
		
    	try{
    		DSpaceObject collection = DSpaceObject.find(context, Constants.COLLECTION, collection_id);
    		
    		if(user_ip==null || user_ip.length()==0){
    			new DSpace().getEventService().fireEvent(
	                     new UsageEvent(
	                                     UsageEvent.Action.VIEW,
	                                     request,
	                                     context,
	                                     collection));
    		} else{
	    		new DSpace().getEventService().fireEvent(
	                     new UsageEvent(
	                                     UsageEvent.Action.VIEW,
	                                     user_ip,
	                                     user_agent,
	                                     xforwarderfor,
	                                     context,
	                                     collection));
    		}
    		log.debug("fired event");
    		
		} catch(SQLException ex){
			log.error("SQL exception can't write usageEvent \n" + ex);
		}
    		
	}
}
