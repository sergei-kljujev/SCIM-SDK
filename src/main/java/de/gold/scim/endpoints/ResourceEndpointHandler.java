package de.gold.scim.endpoints;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import org.apache.commons.lang3.StringUtils;

import com.fasterxml.jackson.databind.JsonNode;

import de.gold.scim.constants.ScimType;
import de.gold.scim.constants.enums.SortOrder;
import de.gold.scim.endpoints.base.ResourceTypeEndpointDefinition;
import de.gold.scim.endpoints.base.SchemaEndpointDefinition;
import de.gold.scim.endpoints.base.ServiceProviderEndpointDefinition;
import de.gold.scim.exceptions.BadRequestException;
import de.gold.scim.exceptions.IOException;
import de.gold.scim.exceptions.InternalServerException;
import de.gold.scim.exceptions.NotImplementedException;
import de.gold.scim.exceptions.ResourceNotFoundException;
import de.gold.scim.exceptions.ScimException;
import de.gold.scim.filter.FilterNode;
import de.gold.scim.filter.resources.FilterResourceResolver;
import de.gold.scim.request.SearchRequest;
import de.gold.scim.resources.ResourceNode;
import de.gold.scim.resources.ServiceProvider;
import de.gold.scim.resources.ServiceProviderUrlExtension;
import de.gold.scim.resources.complex.Meta;
import de.gold.scim.response.CreateResponse;
import de.gold.scim.response.DeleteResponse;
import de.gold.scim.response.ErrorResponse;
import de.gold.scim.response.GetResponse;
import de.gold.scim.response.ListResponse;
import de.gold.scim.response.PartialListResponse;
import de.gold.scim.response.ScimResponse;
import de.gold.scim.response.UpdateResponse;
import de.gold.scim.schemas.ResourceType;
import de.gold.scim.schemas.ResourceTypeFactory;
import de.gold.scim.schemas.SchemaAttribute;
import de.gold.scim.schemas.SchemaValidator;
import de.gold.scim.utils.JsonHelper;
import de.gold.scim.utils.RequestUtils;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;


/**
 * author Pascal Knueppel <br>
 * created at: 03.10.2019 - 19:28 <br>
 * <br>
 * This class is used to execute the SCIM operations on the specific endpoints
 */
@Slf4j
class ResourceEndpointHandler
{

  /**
   * each created {@link de.gold.scim.endpoints.ResourceEndpointHandler} must get hold of a single
   * {@link ServiceProvider} instance which holds the configuration of this service provider implementation
   */
  @Getter
  private final ServiceProvider serviceProvider;

  /**
   * this is used to prevent application context pollution in unit tests
   */
  @Getter(AccessLevel.PROTECTED)
  private ResourceTypeFactory resourceTypeFactory;

  /**
   * this constructor was introduced for unit tests to add a specific resourceTypeFactory instance which will
   * prevent application context pollution within unit tests
   */
  protected ResourceEndpointHandler(ServiceProvider serviceProvider, EndpointDefinition... endpointDefinitions)
  {
    this.resourceTypeFactory = new ResourceTypeFactory();
    this.serviceProvider = serviceProvider;
    if (endpointDefinitions == null || endpointDefinitions.length == 0)
    {
      throw new InternalServerException("At least 1 endpoint must be registered!", null, null);
    }
    List<EndpointDefinition> endpointDefinitionList = new ArrayList<>(Arrays.asList(endpointDefinitions));
    endpointDefinitionList.add(0, new ServiceProviderEndpointDefinition(serviceProvider));
    endpointDefinitionList.add(1, new ResourceTypeEndpointDefinition(resourceTypeFactory));
    endpointDefinitionList.add(2, new SchemaEndpointDefinition(resourceTypeFactory));
    endpointDefinitionList.forEach(this::registerEndpoint);
  }

  /**
   * registers a new endpoint
   *
   * @param endpointDefinition the endpoint to register that will override an existing one if one is already
   *          present
   */
  public void registerEndpoint(EndpointDefinition endpointDefinition)
  {
    resourceTypeFactory.registerResourceType(endpointDefinition.getResourceHandler(),
                                             endpointDefinition.getResourceType(),
                                             endpointDefinition.getResourceSchema(),
                                             endpointDefinition.getResourceSchemaExtensions().toArray(new JsonNode[0]));
  }

  /**
   * checks if a resource type exists under the given endpoint and validates the request if it does by the
   * corresponding meta schema. If the validation succeeds the single json nodes expanded with its meta
   * information will be given to the developer custom implementation. The returned object for the response will
   * be validated again and then returned as a SCIM response
   *
   * @param endpoint the resource endpoint that was called
   * @param resourceDocument the resource document
   * @return the scim response for the client
   */
  protected ScimResponse createResource(String endpoint, String resourceDocument)
  {
    return createResource(endpoint, resourceDocument, null, null, null);
  }

  /**
   * checks if a resource type exists under the given endpoint and validates the request if it does by the
   * corresponding meta schema. If the validation succeeds the single json nodes expanded with its meta
   * information will be given to the developer custom implementation. The returned object for the response will
   * be validated again and then returned as a SCIM response
   *
   * @param endpoint the resource endpoint that was called
   * @param resourceDocument the resource document
   * @param baseUrlSupplier this supplier is an optional attribute that should be used to supply the information
   *          of the base URL of this application e.g.: https://example.com/scim/v2. This return value will be
   *          used to create the location URL of the resources like 'https://example.com/scim/v2/Users/123456'.
   *          If this parameter is not present the application will try to read a hardcoded URL from the service
   *          provider configuration that is also an optional attribute. If both ways fail an exception will be
   *          thrown
   * @return the scim response for the client
   */
  protected ScimResponse createResource(String endpoint, String resourceDocument, Supplier<String> baseUrlSupplier)
  {
    return createResource(endpoint, resourceDocument, null, null, baseUrlSupplier);
  }

  /**
   * checks if a resource type exists under the given endpoint and validates the request if it does by the
   * corresponding meta schema. If the validation succeeds the single json nodes expanded with its meta
   * information will be given to the developer custom implementation. The returned object for the response will
   * be validated again and then returned as a SCIM response
   *
   * @param endpoint the resource endpoint that was called
   * @param resourceDocument the resource document
   * @param attributes When specified, the default list of attributes SHALL be overridden, and each resource
   *          returned MUST contain the minimum set of resource attributes and any attributes or sub-attributes
   *          explicitly requested by the "attributes" parameter. The query parameter attributes value is a
   *          comma-separated list of resource attribute names in standard attribute notation (Section 3.10)
   *          form (e.g., userName, name, emails).
   * @param excludedAttributes When specified, each resource returned MUST contain the minimum set of resource
   *          attributes. Additionally, the default set of attributes minus those attributes listed in
   *          "excludedAttributes" is returned. The query parameter attributes value is a comma-separated list
   *          of resource attribute names in standard attribute notation (Section 3.10) form (e.g., userName,
   *          name, emails).
   * @param baseUrlSupplier this supplier is an optional attribute that should be used to supply the information
   *          of the base URL of this application e.g.: https://example.com/scim/v2. This return value will be
   *          used to create the location URL of the resources like 'https://example.com/scim/v2/Users/123456'.
   *          If this parameter is not present the application will try to read a hardcoded URL from the service
   *          provider configuration that is also an optional attribute. If both ways fail an exception will be
   *          thrown
   * @return the scim response for the client
   */
  protected ScimResponse createResource(String endpoint,
                                        String resourceDocument,
                                        String attributes,
                                        String excludedAttributes,
                                        Supplier<String> baseUrlSupplier)
  {
    try
    {
      if (StringUtils.isBlank(resourceDocument))
      {
        throw new BadRequestException("the request body is empty", null, ScimType.Custom.INVALID_PARAMETERS);
      }
      RequestUtils.validateAttributesAndExcludedAttributes(attributes, excludedAttributes);
      ResourceType resourceType = getResourceType(endpoint);
      JsonNode resource;
      try
      {
        resource = JsonHelper.readJsonDocument(resourceDocument);
      }
      catch (IOException ex)
      {
        throw new BadRequestException(ex.getMessage(), ex, ScimType.Custom.UNPARSEABLE_REQUEST);
      }
      resource = SchemaValidator.validateDocumentForRequest(resourceTypeFactory,
                                                            resourceType,
                                                            resource,
                                                            SchemaValidator.HttpMethod.POST);
      ResourceHandler resourceHandler = resourceType.getResourceHandlerImpl();
      ResourceNode resourceNode = (ResourceNode)JsonHelper.copyResourceToObject(resource, resourceHandler.getType());
      resourceNode.setMeta(getMeta(resourceType));
      resourceNode = resourceHandler.createResource(resourceNode);
      if (resourceNode == null)
      {
        throw new NotImplementedException("create was not implemented for resourceType '" + resourceType.getName()
                                          + "'");
      }
      Supplier<String> errorMessage = () -> "ID attribute not set on created resource";
      String resourceId = resourceNode.getId()
                                      .orElseThrow(() -> new InternalServerException(errorMessage.get(), null, null));
      final String location = getLocation(resourceType, resourceId, baseUrlSupplier);
      Supplier<String> metaErrorMessage = () -> "Meta attribute not set on created resource";
      Meta meta = resourceNode.getMeta()
                              .orElseThrow(() -> new InternalServerException(metaErrorMessage.get(), null, null));
      meta.setLocation(location);
      JsonNode responseResource = SchemaValidator.validateDocumentForResponse(resourceTypeFactory,
                                                                              resourceType,
                                                                              resourceNode,
                                                                              resource,
                                                                              attributes,
                                                                              excludedAttributes);
      return new CreateResponse(responseResource, location);
    }
    catch (ScimException ex)
    {
      return new ErrorResponse(ex);
    }
    catch (Exception ex)
    {
      return new ErrorResponse(new InternalServerException(ex.getMessage(), ex, null));
    }
  }

  /**
   * checks if a resource type exists under the given endpoint and will then give the id to the developers
   * custom implementation stored under the found resource type. The returned {@link ResourceNode} will then be
   * validated and eventually returned to the client
   *
   * @param endpoint the resource endpoint that was called
   * @param id the id of the resource that was requested
   * @return the scim response for the client
   */
  protected ScimResponse getResource(String endpoint, String id)
  {
    return getResource(endpoint, id, null, null, null);
  }

  /**
   * checks if a resource type exists under the given endpoint and will then give the id to the developers
   * custom implementation stored under the found resource type. The returned {@link ResourceNode} will then be
   * validated and eventually returned to the client
   *
   * @param endpoint the resource endpoint that was called
   * @param id the id of the resource that was requested
   * @param baseUrlSupplier this supplier is an optional attribute that should be used to supply the information
   *          of the base URL of this application e.g.: https://example.com/scim/v2. This return value will be
   *          used to create the location URL of the resources like 'https://example.com/scim/v2/Users/123456'.
   *          If this parameter is not present the application will try to read a hardcoded URL from the service
   *          provider configuration that is also an optional attribute. If both ways fail an exception will be
   *          thrown
   * @return the scim response for the client
   */
  protected ScimResponse getResource(String endpoint, String id, Supplier<String> baseUrlSupplier)
  {
    return getResource(endpoint, id, null, null, baseUrlSupplier);
  }

  /**
   * checks if a resource type exists under the given endpoint and will then give the id to the developers
   * custom implementation stored under the found resource type. The returned {@link ResourceNode} will then be
   * validated and eventually returned to the client
   *
   * @param endpoint the resource endpoint that was called
   * @param id the id of the resource that was requested
   * @param attributes When specified, the default list of attributes SHALL be overridden, and each resource
   *          returned MUST contain the minimum set of resource attributes and any attributes or sub-attributes
   *          explicitly requested by the "attributes" parameter. The query parameter attributes value is a
   *          comma-separated list of resource attribute names in standard attribute notation (Section 3.10)
   *          form (e.g., userName, name, emails).
   * @param excludedAttributes When specified, each resource returned MUST contain the minimum set of resource
   *          attributes. Additionally, the default set of attributes minus those attributes listed in
   *          "excludedAttributes" is returned. The query parameter attributes value is a comma-separated list
   *          of resource attribute names in standard attribute notation (Section 3.10) form (e.g., userName,
   *          name, emails).
   * @param baseUrlSupplier this supplier is an optional attribute that should be used to supply the information
   *          of the base URL of this application e.g.: https://example.com/scim/v2. This return value will be
   *          used to create the location URL of the resources like 'https://example.com/scim/v2/Users/123456'.
   *          If this parameter is not present the application will try to read a hardcoded URL from the service
   *          provider configuration that is also an optional attribute. If both ways fail an exception will be
   *          thrown
   * @return the scim response for the client
   */
  protected ScimResponse getResource(String endpoint,
                                     String id,
                                     String attributes,
                                     String excludedAttributes,
                                     Supplier<String> baseUrlSupplier)
  {
    try
    {
      RequestUtils.validateAttributesAndExcludedAttributes(attributes, excludedAttributes);
      ResourceType resourceType = getResourceType(endpoint);
      ResourceHandler resourceHandler = resourceType.getResourceHandlerImpl();
      ResourceNode resourceNode = resourceHandler.getResource(id);
      if (resourceNode == null)
      {
        throw new ResourceNotFoundException("the '" + resourceType.getName() + "' resource with id '" + id + "' does "
                                            + "not exist", null, null);
      }
      String resourceId = resourceNode.getId().orElse(null);
      if (StringUtils.isBlank(resourceId) || !resourceId.equals(id))
      {
        throw new InternalServerException("the id of the returned resource does not match the "
                                          + "requested id: requestedId: '" + id + "', returnedId: '" + resourceId + "'",
                                          null, null);
      }
      final String location = getLocation(resourceType, resourceId, baseUrlSupplier);
      Meta meta = resourceNode.getMeta().orElse(getMeta(resourceType));
      meta.setLocation(location);
      resourceNode.setMeta(meta);
      JsonNode responseResource = SchemaValidator.validateDocumentForResponse(resourceTypeFactory,
                                                                              resourceType,
                                                                              resourceNode,
                                                                              null,
                                                                              attributes,
                                                                              excludedAttributes);
      return new GetResponse(responseResource, location);
    }
    catch (ScimException ex)
    {
      return new ErrorResponse(ex);
    }
    catch (Exception ex)
    {
      return new ErrorResponse(new InternalServerException(ex.getMessage(), ex, null));
    }
  }

  /**
   * Clients MAY execute queries without passing parameters on the URL by using the HTTP POST verb combined with
   * the "/.search" path extension. The inclusion of "/.search" on the end of a valid SCIM endpoint SHALL be
   * used to indicate that the HTTP POST verb is intended to be a query operation.
   *
   * @param endpoint the resource endpoint that was called. This string should only contain the
   *          resources-endpoint not the "/.search" extension e.g. "/Users" or "Users".
   * @param searchRequest the JSON request body of the search request if the request was sent over POST
   * @param baseUrlSupplier this supplier is an optional attribute that should be used to supply the information
   *          of the base URL of this application e.g.: https://example.com/scim/v2. This return value will be
   *          used to create the location URL of the resources like 'https://example.com/scim/v2/Users/123456'.
   *          If this parameter is not present the application will try to read a hardcoded URL from the service
   *          provider configuration that is also an optional attribute. If both ways fail an exception will be
   *          thrown
   * @return a {@link ListResponse} with all returned resources or an {@link ErrorResponse}
   */
  protected ScimResponse listResources(String endpoint, String searchRequest, Supplier<String> baseUrlSupplier)
  {
    return listResources(endpoint, JsonHelper.readJsonDocument(searchRequest, SearchRequest.class), baseUrlSupplier);
  }

  /**
   * Clients MAY execute queries without passing parameters on the URL by using the HTTP POST verb combined with
   * the "/.search" path extension. The inclusion of "/.search" on the end of a valid SCIM endpoint SHALL be
   * used to indicate that the HTTP POST verb is intended to be a query operation.
   *
   * @param endpoint the resource endpoint that was called. This string should only contain the
   *          resources-endpoint * not the "/.search" extension e.g. "/Users" or "Users".
   * @param searchRequest the JSON request body of the search request if the request was sent over POST
   * @param baseUrlSupplier this supplier is an optional attribute that should be used to supply the information
   *          of the base URL of this application e.g.: https://example.com/scim/v2. This return value will be
   *          used to create the location URL of the resources like 'https://example.com/scim/v2/Users/123456'.
   *          If this parameter is not present the application will try to read a hardcoded URL from the service
   *          provider configuration that is also an optional attribute. If both ways fail an exception will be
   *          thrown
   * @return a {@link ListResponse} with all returned resources or an {@link ErrorResponse}
   */
  protected ScimResponse listResources(String endpoint, SearchRequest searchRequest, Supplier<String> baseUrlSupplier)
  {
    return listResources(endpoint,
                         searchRequest.getStartIndex().orElse(null),
                         searchRequest.getCount().orElse(null),
                         searchRequest.getFilter().orElse(null),
                         searchRequest.getSortBy().orElse(null),
                         searchRequest.getSortOrder().orElse(null),
                         searchRequest.getAttributes().orElse(null),
                         searchRequest.getExcludedAttributes().orElse(null),
                         baseUrlSupplier);
  }

  /**
   * Clients MAY execute queries without passing parameters on the URL by using the HTTP POST verb combined with
   * the "/.search" path extension. The inclusion of "/.search" on the end of a valid SCIM endpoint SHALL be
   * used to indicate that the HTTP POST verb is intended to be a query operation.
   *
   * @param endpoint the resource endpoint that was called e.g. "/Users" or "Users".
   * @param startIndex The 1-based index of the first query result. A value less than 1 SHALL be interpreted as
   *          1.<br>
   *          <b>DEFAULT:</b> 1
   * @param count Non-negative integer. Specifies the desired maximum number of query results per page, e.g.,
   *          10. A negative value SHALL be interpreted as "0". A value of "0" indicates that no resource
   *          results are to be returned except for "totalResults". <br>
   *          <b>DEFAULT:</b> None<br>
   *          When specified, the service provider MUST NOT return more results than specified, although it MAY
   *          return fewer results. If unspecified, the maximum number of results is set by the service
   *          provider.
   * @param filter Filtering is an OPTIONAL parameter for SCIM service providers. Clients MAY discover service
   *          provider filter capabilities by looking at the "filter" attribute of the "ServiceProviderConfig"
   *          endpoint. Clients MAY request a subset of resources by specifying the "filter" query parameter
   *          containing a filter expression. When specified, only those resources matching the filter
   *          expression SHALL be returned. The expression language that is used with the filter parameter
   *          supports references to attributes and literals.
   * @param sortBy The "sortBy" parameter specifies the attribute whose value SHALL be used to order the
   *          returned responses. If the "sortBy" attribute corresponds to a singular attribute, resources are
   *          sorted according to that attribute's value; if it's a multi-valued attribute, resources are sorted
   *          by the value of the primary attribute (see Section 2.4 of [RFC7643]), if any, or else the first
   *          value in the list, if any. If the attribute is complex, the attribute name must be a path to a
   *          sub-attribute in standard attribute notation (Section 3.10), e.g., "sortBy=name.givenName". For
   *          all attribute types, if there is no data for the specified "sortBy" value, they are sorted via the
   *          "sortOrder" parameter, i.e., they are ordered last if ascending and first if descending.
   * @param sortOrder The order in which the "sortBy" parameter is applied. Allowed values are "ascending" and
   *          "descending". If a value for "sortBy" is provided and no "sortOrder" is specified, "sortOrder"
   *          SHALL default to ascending. String type attributes are case insensitive by default, unless the
   *          attribute type is defined as a case-exact string. "sortOrder" MUST sort according to the attribute
   *          type; i.e., for case-insensitive attributes, sort the result using case-insensitive Unicode
   *          alphabetic sort order with no specific locale implied, and for case-exact attribute types, sort
   *          the result using case-sensitive Unicode alphabetic sort order.
   * @param attributes When specified, the default list of attributes SHALL be overridden, and each resource
   *          returned MUST contain the minimum set of resource attributes and any attributes or sub-attributes
   *          explicitly requested by the "attributes" parameter. The query parameter attributes value is a
   *          comma-separated list of resource attribute names in standard attribute notation (Section 3.10)
   *          form (e.g., userName, name, emails).
   * @param excludedAttributes When specified, each resource returned MUST contain the minimum set of resource
   *          attributes. Additionally, the default set of attributes minus those attributes listed in
   *          "excludedAttributes" is returned. The query parameter attributes value is a comma-separated list
   *          of resource attribute names in standard attribute notation (Section 3.10) form (e.g., userName,
   *          name, emails).
   * @param baseUrlSupplier this supplier is an optional attribute that should be used to supply the information
   *          of the base URL of this application e.g.: https://example.com/scim/v2. This return value will be
   *          used to create the location URL of the resources like 'https://example.com/scim/v2/Users/123456'.
   *          If this parameter is not present the application will try to read a hardcoded URL from the service
   *          provider configuration that is also an optional attribute. If both ways fail an exception will be
   *          thrown
   * @return a {@link ListResponse} with all returned resources or an {@link ErrorResponse}
   */
  protected <T extends ResourceNode> ScimResponse listResources(String endpoint,
                                                                Long startIndex,
                                                                Integer count,
                                                                String filter,
                                                                String sortBy,
                                                                String sortOrder,
                                                                String attributes,
                                                                String excludedAttributes,
                                                                Supplier<String> baseUrlSupplier)
  {
    try
    {
      final ResourceType resourceType = getResourceType(endpoint);
      final long effectiveStartIndex = RequestUtils.getEffectiveStartIndex(startIndex);
      final int effectiveCount = RequestUtils.getEffectiveCount(serviceProvider, count);
      final FilterNode filterNode = getFilterNode(resourceType, filter);
      final boolean autoFiltering = resourceType.getFilterExtension()
                                                .map(ResourceType.FilterExtension::isAutoFiltering)
                                                .orElse(false);
      final SchemaAttribute sortByAttribute = getSortByAttribute(resourceType, sortBy);
      final SortOrder sortOrdering = getSortOrdering(sortOrder, sortByAttribute);

      ResourceHandler<T> resourceHandler = resourceType.getResourceHandlerImpl();
      PartialListResponse<T> resources = resourceHandler.listResources(effectiveStartIndex,
                                                                       effectiveCount,
                                                                       autoFiltering ? null : filterNode,
                                                                       sortByAttribute,
                                                                       sortOrdering);
      if (resources == null)
      {
        throw new NotImplementedException("listResources was not implemented for resourceType '"
                                          + resourceType.getName() + "'");
      }

      List<T> resourceList = resources.getResources();
      List<T> filteredResources = filterResources(filterNode, resourceList, resourceType);

      long totalResults = resourceList.size() != filteredResources.size() ? filteredResources.size()
        : (resources.getTotalResults() == 0 ? filteredResources.size() : resources.getTotalResults());

      // this if-block will assert that no more results will be returned than the countValue allows.
      if (effectiveStartIndex <= filteredResources.size())
      {
        filteredResources = filteredResources.subList((int)Math.min(effectiveStartIndex - 1,
                                                                    filteredResources.size() - 1),
                                                      (int)Math.min(effectiveStartIndex - 1 + effectiveCount,
                                                                    filteredResources.size()));
      }
      else
      {
        // startIndex is greater than the number of entries available so we will return an empty list
        filteredResources = Collections.emptyList();
      }
      if (filteredResources.size() > effectiveCount)
      {
        log.warn("the service provider tried to return more results than allowed. Tried to return '"
                 + filteredResources.size() + "' results. The list will be reduced to '" + effectiveCount
                 + "' results");
        filteredResources = filteredResources.subList(0, effectiveCount);
      }

      List<JsonNode> validatedResourceList = new ArrayList<>();
      for ( ResourceNode resourceNode : filteredResources )
      {
        final String location = getLocation(resourceType, resourceNode.getId().orElse(null), baseUrlSupplier);
        Meta meta = resourceNode.getMeta().orElse(getMeta(resourceType));
        meta.setLocation(location);
        resourceNode.setMeta(meta);
        JsonNode validatedResource = SchemaValidator.validateDocumentForResponse(resourceTypeFactory,
                                                                                 resourceType,
                                                                                 resourceNode,
                                                                                 null,
                                                                                 attributes,
                                                                                 excludedAttributes);
        validatedResourceList.add(validatedResource);
      }

      return new ListResponse(validatedResourceList, totalResults, effectiveCount, effectiveStartIndex);
    }
    catch (ScimException ex)
    {
      return new ErrorResponse(ex);
    }
    catch (Exception ex)
    {
      return new ErrorResponse(new InternalServerException(ex.getMessage(), ex, null));
    }
  }

  /**
   * this method executes filtering on the given resource list
   *
   * @param filterNode the filter expression from the client. Might be null if filtering is disabled
   * @param resourceList the list that should be filtered
   * @param resourceType the resource type must have filtering enabled. If filtering is not explicitly enabled
   *          the developer must do the filtering manually
   * @return the filtered list or the {@code resourceList}
   */
  protected <T extends ResourceNode> List<T> filterResources(FilterNode filterNode,
                                                             List<T> resourceList,
                                                             ResourceType resourceType)
  {
    boolean isApplicationFilteringEnabled = resourceType.getFilterExtension()
                                                        .map(ResourceType.FilterExtension::isAutoFiltering)
                                                        .orElse(false);
    List<T> filteredResourceType;
    if (isApplicationFilteringEnabled && filterNode != null)
    {
      filteredResourceType = FilterResourceResolver.filterResources(resourceList, filterNode);
    }
    else
    {
      filteredResourceType = resourceList;
    }
    return filteredResourceType;
  }

  /**
   * checks if the given filter expression must be evaluated or not. If the filtering is disabled in the
   * {@link ServiceProvider} instance the filter expression is ignored and a debug message is printed
   *
   * @param resourceType the resource type on which the filter expression should be evaluated
   * @param filter the filter expression that should be evaluated
   * @return a filter tree structure that makes resolving the filter very easy
   */
  private FilterNode getFilterNode(ResourceType resourceType, String filter)
  {
    if (StringUtils.isBlank(filter))
    {
      return null;
    }
    if (serviceProvider.getFilterConfig().isSupported())
    {
      return RequestUtils.parseFilter(resourceType, filter);
    }
    log.debug("filter expression '{}' is not evaluated because filter support is disabled", filter);
    return null;
  }

  /**
   * will parse the sortBy value to a {@link SchemaAttribute} from the {@link ResourceType} if sorting is
   * enabled and the sortBy attribute is provided
   *
   * @param resourceType the resourceType to which the sortBy value must apply
   * @param sortBy the sortBy value that will be evaluated
   * @return either the {@link SchemaAttribute} that applies to the sortBy value or null
   */
  private SchemaAttribute getSortByAttribute(ResourceType resourceType, String sortBy)
  {
    if (StringUtils.isBlank(sortBy))
    {
      return null;
    }
    if (serviceProvider.getSortConfig().isSupported())
    {
      return RequestUtils.getSchemaAttributeForSortBy(resourceType, sortBy);
    }
    log.debug("sortBy value '{}' is ignored because sorting support is disabled", sortBy);
    return null;
  }

  /**
   * will parse the sortOrdering value to a {@link SortOrder} instance if sorting is enabled and the sortOrder
   * value is provided. If the sortBy value is not null and was evaluated before but the sortOrder value is not
   * present it will default to value {@link SortOrder#ASCENDING}
   *
   * @param sortOrder the sortOrder value specified by the client
   * @param sortBy the sortBy value specified by the client
   * @return the sortOrder value if the value was specified and sorting is enabled or null
   */
  private SortOrder getSortOrdering(String sortOrder, SchemaAttribute sortBy)
  {
    if (StringUtils.isBlank(sortOrder))
    {
      if (sortBy != null)
      {
        // If a value for "sortBy" is provided and no "sortOrder" is specified, "sortOrder" SHALL default to ascending
        return SortOrder.ASCENDING;
      }
    }
    if (serviceProvider.getSortConfig().isSupported())
    {
      return SortOrder.getByValue(sortOrder);
    }
    log.debug("sortOrder value '{}' is ignored because sorting support is disabled", sortOrder);
    return null;
  }

  /**
   * checks if a resource type exists under the given endpoint and validates the request if it does by the
   * corresponding meta schema. If the validation succeeds the single json nodes expanded with its meta
   * information will be given to the developer custom implementation. The returned object for the response will
   * be validated again and then returned as a SCIM response
   *
   * @param endpoint the resource endpoint that was called
   * @param id the id of the resource that was requested
   * @param resourceDocument the resource document
   * @return the scim response for the client
   */
  protected ScimResponse updateResource(String endpoint, String id, String resourceDocument)
  {
    return updateResource(endpoint, id, resourceDocument, null, null, null);
  }

  /**
   * checks if a resource type exists under the given endpoint and validates the request if it does by the
   * corresponding meta schema. If the validation succeeds the single json nodes expanded with its meta
   * information will be given to the developer custom implementation. The returned object for the response will
   * be validated again and then returned as a SCIM response
   *
   * @param endpoint the resource endpoint that was called
   * @param id the id of the resource that was requested
   * @param resourceDocument the resource document
   * @param baseUrlSupplier this supplier is an optional attribute that should be used to supply the information
   *          of the base URL of this application e.g.: https://example.com/scim/v2. This return value will be
   *          used to create the location URL of the resources like 'https://example.com/scim/v2/Users/123456'.
   *          If this parameter is not present the application will try to read a hardcoded URL from the service
   *          provider configuration that is also an optional attribute. If both ways fail an exception will be
   *          thrown
   * @return the scim response for the client
   */
  protected ScimResponse updateResource(String endpoint,
                                        String id,
                                        String resourceDocument,
                                        Supplier<String> baseUrlSupplier)
  {
    return updateResource(endpoint, id, resourceDocument, null, null, baseUrlSupplier);
  }

  /**
   * checks if a resource type exists under the given endpoint and validates the request if it does by the
   * corresponding meta schema. If the validation succeeds the single json nodes expanded with its meta
   * information will be given to the developer custom implementation. The returned object for the response will
   * be validated again and then returned as a SCIM response
   *
   * @param endpoint the resource endpoint that was called
   * @param id the id of the resource that was requested
   * @param resourceDocument the resource document
   * @param attributes When specified, the default list of attributes SHALL be overridden, and each resource
   *          returned MUST contain the minimum set of resource attributes and any attributes or sub-attributes
   *          explicitly requested by the "attributes" parameter. The query parameter attributes value is a
   *          comma-separated list of resource attribute names in standard attribute notation (Section 3.10)
   *          form (e.g., userName, name, emails).
   * @param excludedAttributes When specified, each resource returned MUST contain the minimum set of resource
   *          attributes. Additionally, the default set of attributes minus those attributes listed in
   *          "excludedAttributes" is returned. The query parameter attributes value is a comma-separated list
   *          of resource attribute names in standard attribute notation (Section 3.10) form (e.g., userName,
   *          name, emails).
   * @param baseUrlSupplier this supplier is an optional attribute that should be used to supply the information
   *          of the base URL of this application e.g.: https://example.com/scim/v2. This return value will be
   *          used to create the location URL of the resources like 'https://example.com/scim/v2/Users/123456'.
   *          If this parameter is not present the application will try to read a hardcoded URL from the service
   *          provider configuration that is also an optional attribute. If both ways fail an exception will be
   *          thrown
   * @return the scim response for the client
   */
  protected ScimResponse updateResource(String endpoint,
                                        String id,
                                        String resourceDocument,
                                        String attributes,
                                        String excludedAttributes,
                                        Supplier<String> baseUrlSupplier)
  {
    try
    {
      if (StringUtils.isBlank(resourceDocument))
      {
        throw new BadRequestException("the request body is empty", null, ScimType.Custom.INVALID_PARAMETERS);
      }
      RequestUtils.validateAttributesAndExcludedAttributes(attributes, excludedAttributes);
      ResourceType resourceType = getResourceType(endpoint);
      JsonNode resource;
      try
      {
        resource = JsonHelper.readJsonDocument(resourceDocument);
      }
      catch (IOException ex)
      {
        throw new BadRequestException(ex.getMessage(), ex, ScimType.Custom.UNPARSEABLE_REQUEST);
      }
      resource = SchemaValidator.validateDocumentForRequest(resourceTypeFactory,
                                                            resourceType,
                                                            resource,
                                                            SchemaValidator.HttpMethod.PUT);
      ResourceHandler resourceHandler = resourceType.getResourceHandlerImpl();
      if (resource == null)
      {
        throw new BadRequestException("the request body does not contain any writable parameters", null,
                                      ScimType.Custom.UNPARSEABLE_REQUEST);
      }
      ResourceNode resourceNode = (ResourceNode)JsonHelper.copyResourceToObject(resource, resourceHandler.getType());
      if (resourceNode == null)
      {
        throw new ResourceNotFoundException("the '" + resourceType.getName() + "' resource with id '" + id + "' does "
                                            + "not exist", null, null);
      }
      resourceNode.setId(id);
      final String location = getLocation(resourceType, id, baseUrlSupplier);
      resourceNode.setMeta(getMeta(resourceType));
      resourceNode = resourceHandler.updateResource(resourceNode);
      if (resourceNode == null)
      {
        throw new ResourceNotFoundException("the '" + resourceType.getName() + "' resource with id '" + id + "' does "
                                            + "not exist", null, null);
      }
      Supplier<String> errorMessage = () -> "ID attribute not set on updated resource";
      String resourceId = resourceNode.getId()
                                      .orElseThrow(() -> new InternalServerException(errorMessage.get(), null, null));
      if (!resourceId.equals(id))
      {
        throw new InternalServerException("the id of the returned resource does not match the "
                                          + "requested id: requestedId: '" + id + "', returnedId: '" + resourceId + "'",
                                          null, null);
      }
      Meta meta = resourceNode.getMeta().orElse(getMeta(resourceType));
      meta.setLocation(location);
      resourceNode.setMeta(meta);
      JsonNode responseResource = SchemaValidator.validateDocumentForResponse(resourceTypeFactory,
                                                                              resourceType,
                                                                              resourceNode,
                                                                              resource,
                                                                              attributes,
                                                                              excludedAttributes);

      return new UpdateResponse(responseResource, location);
    }
    catch (ScimException ex)
    {
      return new ErrorResponse(ex);
    }
    catch (Exception ex)
    {
      return new ErrorResponse(new InternalServerException(ex.getMessage(), ex, null));
    }
  }

  /**
   * checks if a resource type exists under the given endpoint and will then give the id to the developers
   * custom implementation stored under the found resource type. If no exception occurred the client will be
   * informed of a successful request
   *
   * @param endpoint the resource endpoint that was called
   * @param id the id of the resource that was requested
   * @return an empty response that does not create a response body
   */
  protected ScimResponse deleteResource(String endpoint, String id)
  {
    try
    {
      ResourceType resourceType = getResourceType(endpoint);
      ResourceHandler resourceHandler = resourceType.getResourceHandlerImpl();
      resourceHandler.deleteResource(id);
      return new DeleteResponse();
    }
    catch (ScimException ex)
    {
      return new ErrorResponse(ex);
    }
    catch (Exception ex)
    {
      return new ErrorResponse(new InternalServerException(ex.getMessage(), ex, null));
    }
  }

  /**
   * tries to extract the resource type by its endpoint path suffix e.g. "/Users" or "/Groups"
   *
   * @param endpoint the endpoint path suffix
   * @return the resource type registered for the given path
   */
  private ResourceType getResourceType(String endpoint)
  {
    Supplier<String> errorMessage = () -> "no resource found for endpoint '" + endpoint + "'";
    return Optional.ofNullable(resourceTypeFactory.getResourceType(endpoint))
                   .orElseThrow(() -> new BadRequestException(errorMessage.get(), null,
                                                              ScimType.Custom.UNKNOWN_RESOURCE));
  }

  /**
   * creates the meta representation for the response
   *
   * @param resourceType the resource type that holds necessary data like the name of the resource
   * @return the meta json representation
   */
  private Meta getMeta(ResourceType resourceType)
  {
    return Meta.builder().resourceType(resourceType.getName()).build();
  }

  /**
   * builds the location attribute for the meta-object
   *
   * @param resourceType holds the endpoint definition
   * @param resourceId the id of the resource to which the location should be determined
   * @param getBaseUrlSupplier this supplier is an optional attribute that should be used to supply the
   *          information of the base URL of this application e.g.: https://example.com/scim/v2. This return
   *          value will be used to create the location URL of the resources like
   *          'https://example.com/scim/v2/Users/123456'. If this parameter is not present the application will
   *          try to read a hardcoded URL from the service provider configuration that is also an optional
   *          attribute. If both ways fail an exception will be thrown
   * @return the current location
   */
  private String getLocation(ResourceType resourceType, String resourceId, Supplier<String> getBaseUrlSupplier)
  {
    String baseUrl = getBaseUrlSupplier == null ? null : getBaseUrlSupplier.get();
    if (StringUtils.isBlank(baseUrl))
    {
      baseUrl = serviceProvider.getServiceProviderUrlExtension()
                               .map(ServiceProviderUrlExtension::getBaseUrl)
                               .orElse(null);
      if (StringUtils.isBlank(baseUrl))
      {
        log.warn("TODO set location url");
        return "";
      }
    }
    if (baseUrl.endsWith("/"))
    {
      baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
    }
    return baseUrl + resourceType.getEndpoint() + "/" + resourceId;
  }
}