package com.bitsapplied.descartes.resources;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import javax.management.MBeanAttributeInfo;
import javax.management.MBeanConstructorInfo;
import javax.management.MBeanInfo;
import javax.management.MBeanNotificationInfo;
import javax.management.MBeanOperationInfo;
import javax.management.MBeanParameterInfo;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.management.openmbean.CompositeData;
import javax.management.openmbean.TabularData;

import com.bitsapplied.descartes.util.QueryParams;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * MCP Resource that provides access to JMX MBeans, their attributes,
 * operations, and notifications.
 */
public class MBeanResource implements MCPResourceHandler {
  private static final ObjectMapper mapper = new ObjectMapper();
  private final MBeanServer mBeanServer = ManagementFactory.getPlatformMBeanServer();

  @Override
  public String getUriPath() {
    return "mbeans";
  }

  @Override
  public String getName() {
    return "JMX MBeans";
  }

  @Override
  public String getDescription() {
    return "JMX MBean browser and inspector for monitoring and managing Java application components. "
        + "Lists all registered MBeans in the platform MBean server, reads MBean attributes for monitoring metrics, "
        + "discovers available operations and their parameters, and provides MBean metadata including notifications. "
        + "Parameters: 'domain' (filter by domain), 'object' (specific MBean name), 'attributes' (list specific attributes). "
        + "Essential for accessing runtime metrics, configuration, and management operations exposed via JMX.";
  }

  @Override
  public String getMimeType() {
    return "application/json";
  }

  @Override
  public String handleRequest(QueryParams queryParams) throws MCPResource.ResourceException {
    try {
      String action = queryParams.get("action", "list");

      switch (action) {
      case "list":
        String domain = queryParams.get("domain", "");
        return listMBeans(domain);
      case "info":
        String objectName = queryParams.get("name", "");
        return getMBeanInfo(objectName);
      case "attributes":
        String attrObjectName = queryParams.get("name", "");
        return getMBeanAttributes(attrObjectName);
      case "domains":
        return getDomains();
      case "search":
        String pattern = queryParams.get("pattern", "*:*");
        return searchMBeans(pattern);
      default:
        throw new MCPResource.ResourceException("Unknown action: " + action);
      }
    } catch (MCPResource.ResourceException e) {
      throw e;
    } catch (Exception e) {
      throw new MCPResource.ResourceException("Error handling MBean request", e);
    }
  }

  private String getDomains() throws Exception {
    ObjectNode result = mapper.createObjectNode();
    ArrayNode domainsArray = result.putArray("domains");

    String[] domains = mBeanServer.getDomains();
    Map<String, Integer> domainCounts = new HashMap<>();

    for (String domain : domains) {
      try {
        Set<ObjectName> mbeans = mBeanServer.queryNames(new ObjectName(domain + ":*"), null);
        domainCounts.put(domain, mbeans.size());
      } catch (Exception e) {
        domainCounts.put(domain, 0);
      }
    }

    for (Map.Entry<String, Integer> entry : domainCounts.entrySet()) {
      ObjectNode domainNode = domainsArray.addObject();
      domainNode.put("name", entry.getKey());
      domainNode.put("mbeanCount", entry.getValue());
    }

    result.put("totalDomains", domains.length);
    result.put("totalMBeans", domainCounts.values().stream().mapToInt(Integer::intValue).sum());

    return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result);
  }

  private String listMBeans(String domain) throws Exception {
    ObjectNode result = mapper.createObjectNode();

    String query = domain.isEmpty() ? "*:*" : domain + ":*";
    Set<ObjectName> mbeans = mBeanServer.queryNames(new ObjectName(query), null);

    // Group MBeans by domain
    Map<String, List<ObjectName>> byDomain = mbeans.stream().collect(Collectors.groupingBy(ObjectName::getDomain));

    for (Map.Entry<String, List<ObjectName>> entry : byDomain.entrySet()) {
      ArrayNode domainArray = result.putArray(entry.getKey());

      for (ObjectName name : entry.getValue()) {
        ObjectNode mbeanNode = domainArray.addObject();
        mbeanNode.put("objectName", name.toString());
        mbeanNode.put("canonicalName", name.getCanonicalName());

        // Add key properties
        ObjectNode propertiesNode = mbeanNode.putObject("properties");
        for (Map.Entry<String, String> prop : name.getKeyPropertyList().entrySet()) {
          propertiesNode.put(prop.getKey(), prop.getValue());
        }

        // Try to get basic info
        try {
          MBeanInfo info = mBeanServer.getMBeanInfo(name);
          mbeanNode.put("className", info.getClassName());
          mbeanNode.put("description", info.getDescription());
          mbeanNode.put("attributeCount", info.getAttributes().length);
          mbeanNode.put("operationCount", info.getOperations().length);
        } catch (Exception e) {
          mbeanNode.put("error", "Unable to get MBean info: " + e.getMessage());
        }
      }
    }

    result.put("totalCount", mbeans.size());
    result.put("domainCount", byDomain.size());

    return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result);
  }

  private String getMBeanInfo(String objectNameStr) throws Exception {
    if (objectNameStr == null || objectNameStr.isEmpty()) {
      throw new MCPResource.ResourceException("Object name is required");
    }

    ObjectNode result = mapper.createObjectNode();
    ObjectName objectName = new ObjectName(objectNameStr);

    if (!mBeanServer.isRegistered(objectName)) {
      throw new MCPResource.ResourceException("MBean not found: " + objectNameStr);
    }

    MBeanInfo info = mBeanServer.getMBeanInfo(objectName);

    result.put("objectName", objectNameStr);
    result.put("className", info.getClassName());
    result.put("description", info.getDescription());

    // Attributes
    ArrayNode attributesArray = result.putArray("attributes");
    for (MBeanAttributeInfo attr : info.getAttributes()) {
      ObjectNode attrNode = attributesArray.addObject();
      attrNode.put("name", attr.getName());
      attrNode.put("type", attr.getType());
      attrNode.put("description", attr.getDescription());
      attrNode.put("readable", attr.isReadable());
      attrNode.put("writable", attr.isWritable());
      attrNode.put("is", attr.isIs());

      // Try to get current value if readable
      if (attr.isReadable()) {
        try {
          Object value = mBeanServer.getAttribute(objectName, attr.getName());
          attrNode.put("value", convertValue(value));
        } catch (Exception e) {
          attrNode.put("value", "Error reading: " + e.getMessage());
        }
      }
    }

    // Operations
    ArrayNode operationsArray = result.putArray("operations");
    for (MBeanOperationInfo op : info.getOperations()) {
      ObjectNode opNode = operationsArray.addObject();
      opNode.put("name", op.getName());
      opNode.put("returnType", op.getReturnType());
      opNode.put("description", op.getDescription());
      opNode.put("impact", getImpactString(op.getImpact()));

      ArrayNode paramsArray = opNode.putArray("parameters");
      for (MBeanParameterInfo param : op.getSignature()) {
        ObjectNode paramNode = paramsArray.addObject();
        paramNode.put("name", param.getName());
        paramNode.put("type", param.getType());
        paramNode.put("description", param.getDescription());
      }
    }

    // Constructors
    ArrayNode constructorsArray = result.putArray("constructors");
    for (MBeanConstructorInfo constructor : info.getConstructors()) {
      ObjectNode constructorNode = constructorsArray.addObject();
      constructorNode.put("name", constructor.getName());
      constructorNode.put("description", constructor.getDescription());

      ArrayNode paramsArray = constructorNode.putArray("parameters");
      for (MBeanParameterInfo param : constructor.getSignature()) {
        ObjectNode paramNode = paramsArray.addObject();
        paramNode.put("name", param.getName());
        paramNode.put("type", param.getType());
        paramNode.put("description", param.getDescription());
      }
    }

    // Notifications
    ArrayNode notificationsArray = result.putArray("notifications");
    for (MBeanNotificationInfo notif : info.getNotifications()) {
      ObjectNode notifNode = notificationsArray.addObject();
      notifNode.put("name", notif.getName());
      notifNode.put("description", notif.getDescription());

      ArrayNode typesArray = notifNode.putArray("notifTypes");
      for (String type : notif.getNotifTypes()) {
        typesArray.add(type);
      }
    }

    return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result);
  }

  private String getMBeanAttributes(String objectNameStr) throws Exception {
    if (objectNameStr == null || objectNameStr.isEmpty()) {
      throw new MCPResource.ResourceException("Object name is required");
    }

    ObjectNode result = mapper.createObjectNode();
    ObjectName objectName = new ObjectName(objectNameStr);

    if (!mBeanServer.isRegistered(objectName)) {
      throw new MCPResource.ResourceException("MBean not found: " + objectNameStr);
    }

    result.put("objectName", objectNameStr);

    MBeanInfo info = mBeanServer.getMBeanInfo(objectName);
    ObjectNode attributesNode = result.putObject("attributes");

    for (MBeanAttributeInfo attr : info.getAttributes()) {
      if (attr.isReadable()) {
        try {
          Object value = mBeanServer.getAttribute(objectName, attr.getName());
          attributesNode.put(attr.getName(), convertValue(value));
        } catch (Exception e) {
          attributesNode.put(attr.getName(), "Error: " + e.getMessage());
        }
      }
    }

    result.put("attributeCount", info.getAttributes().length);
    result.put("timestamp", System.currentTimeMillis());

    return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result);
  }

  private String searchMBeans(String pattern) throws Exception {
    ObjectNode result = mapper.createObjectNode();

    ObjectName queryName = new ObjectName(pattern);
    Set<ObjectName> mbeans = mBeanServer.queryNames(queryName, null);

    ArrayNode mbeansArray = result.putArray("mbeans");
    for (ObjectName name : mbeans) {
      ObjectNode mbeanNode = mbeansArray.addObject();
      mbeanNode.put("objectName", name.toString());
      mbeanNode.put("domain", name.getDomain());

      ObjectNode propertiesNode = mbeanNode.putObject("properties");
      for (Map.Entry<String, String> prop : name.getKeyPropertyList().entrySet()) {
        propertiesNode.put(prop.getKey(), prop.getValue());
      }

      try {
        MBeanInfo info = mBeanServer.getMBeanInfo(name);
        mbeanNode.put("className", info.getClassName());
      } catch (Exception e) {
        // Ignore
      }
    }

    result.put("pattern", pattern);
    result.put("matchCount", mbeans.size());

    return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result);
  }

  private String getImpactString(int impact) {
    switch (impact) {
    case MBeanOperationInfo.ACTION:
      return "ACTION";
    case MBeanOperationInfo.ACTION_INFO:
      return "ACTION_INFO";
    case MBeanOperationInfo.INFO:
      return "INFO";
    case MBeanOperationInfo.UNKNOWN:
    default:
      return "UNKNOWN";
    }
  }

  private String convertValue(Object value) {
    if (value == null) {
      return "null";
    }

    if (value instanceof CompositeData) {
      CompositeData cd = (CompositeData) value;
      Map<String, Object> map = new HashMap<>();
      for (String key : cd.getCompositeType().keySet()) {
        map.put(key, convertValue(cd.get(key)));
      }
      return map.toString();
    }

    if (value instanceof TabularData) {
      TabularData td = (TabularData) value;
      List<Map<String, Object>> list = new ArrayList<>();
      for (Object row : td.values()) {
        if (row instanceof CompositeData) {
          CompositeData cd = (CompositeData) row;
          Map<String, Object> map = new HashMap<>();
          for (String key : cd.getCompositeType().keySet()) {
            map.put(key, convertValue(cd.get(key)));
          }
          list.add(map);
        }
      }
      return list.toString();
    }

    if (value.getClass().isArray()) {
      if (value instanceof Object[]) {
        return Arrays.toString((Object[]) value);
      } else if (value instanceof int[]) {
        return Arrays.toString((int[]) value);
      } else if (value instanceof long[]) {
        return Arrays.toString((long[]) value);
      } else if (value instanceof double[]) {
        return Arrays.toString((double[]) value);
      } else if (value instanceof float[]) {
        return Arrays.toString((float[]) value);
      } else if (value instanceof boolean[]) {
        return Arrays.toString((boolean[]) value);
      } else if (value instanceof byte[]) {
        return Arrays.toString((byte[]) value);
      } else if (value instanceof char[]) {
        return Arrays.toString((char[]) value);
      } else if (value instanceof short[]) {
        return Arrays.toString((short[]) value);
      }
    }

    return value.toString();
  }
}