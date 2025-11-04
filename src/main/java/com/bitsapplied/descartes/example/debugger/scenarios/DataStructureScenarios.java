package com.bitsapplied.descartes.example.debugger.scenarios;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Complex data structure scenarios for variable inspection and object
 * expansion.
 *
 * <p>
 * This class demonstrates debugger capabilities for inspecting:
 * <ul>
 * <li>Complex object hierarchies (nested objects)</li>
 * <li>Collections (Lists, Maps, Sets)</li>
 * <li>Nested data structures</li>
 * <li>Circular references</li>
 * <li>Static fields</li>
 * <li>Arrays and multi-dimensional arrays</li>
 * </ul>
 *
 * <h3>Debugging Focus:</h3>
 * <ul>
 * <li>Variable expansion (getChildVariables)</li>
 * <li>Deep object inspection</li>
 * <li>Collection element examination</li>
 * <li>Static field inspection</li>
 * </ul>
 */
public class DataStructureScenarios {

  // Static fields for inspection
  private static final String APP_NAME = "Debugger Demo";
  private static final int MAX_USERS = 100;
  private static int userCount = 0;

  /**
   * Simple object hierarchy: Person -> Address -> City.
   *
   * <p>
   * <b>Try this:</b>
   * <ul>
   * <li>Set breakpoint after person creation</li>
   * <li>Inspect {@code person} variable</li>
   * <li>Expand to see {@code address} field</li>
   * <li>Expand {@code address} to see {@code city} field</li>
   * <li>Expand {@code city} to see all properties</li>
   * </ul>
   */
  public void objectHierarchy() {
    City city = new City("San Francisco", "CA", 873965);
    Address address = new Address("123 Main St", city, "94102");
    Person person = new Person("Alice Johnson", 30, address);

    // Breakpoint here - expand person -> address -> city
    System.out.println("Person: " + person);
    System.out.println("Lives in: " + person.getAddress().getCity().getName());
  }

  /**
   * Collection inspection: Lists, Maps, Sets.
   *
   * <p>
   * <b>Try this:</b>
   * <ul>
   * <li>Set breakpoint after all collections are populated</li>
   * <li>Inspect {@code names} list - expand to see elements</li>
   * <li>Inspect {@code ages} map - expand to see key-value pairs</li>
   * <li>Inspect {@code uniqueIds} set - expand to see elements</li>
   * <li>Evaluate: {@code ages.get("Alice")}</li>
   * </ul>
   */
  public void collectionInspection() {
    // List
    List<String> names = new ArrayList<>();
    names.add("Alice");
    names.add("Bob");
    names.add("Charlie");

    // Map
    Map<String, Integer> ages = new HashMap<>();
    ages.put("Alice", 30);
    ages.put("Bob", 25);
    ages.put("Charlie", 35);

    // Set
    Set<String> uniqueIds = new HashSet<>();
    uniqueIds.add("ID-001");
    uniqueIds.add("ID-002");
    uniqueIds.add("ID-003");

    // Breakpoint here - inspect all collections
    System.out.println("Names: " + names);
    System.out.println("Ages: " + ages);
    System.out.println("IDs: " + uniqueIds);
  }

  /**
   * Nested data structures: Map of Lists.
   *
   * <p>
   * <b>Try this:</b>
   * <ul>
   * <li>Set breakpoint after department setup</li>
   * <li>Inspect {@code departments} map</li>
   * <li>Expand to see department names (keys)</li>
   * <li>Expand each department to see employee list</li>
   * <li>Expand employees to see Person objects</li>
   * <li>Evaluate: {@code departments.get("Engineering").size()}</li>
   * </ul>
   */
  public void nestedStructures() {
    Map<String, List<Person>> departments = new HashMap<>();

    // Engineering department
    List<Person> engineering = new ArrayList<>();
    engineering.add(new Person("Alice", 30, null));
    engineering.add(new Person("Bob", 28, null));
    engineering.add(new Person("Charlie", 32, null));

    // Sales department
    List<Person> sales = new ArrayList<>();
    sales.add(new Person("Dave", 35, null));
    sales.add(new Person("Eve", 29, null));

    departments.put("Engineering", engineering);
    departments.put("Sales", sales);

    // Breakpoint here - expand nested structure
    System.out.println("Departments: " + departments.keySet());
    for (Map.Entry<String, List<Person>> entry : departments.entrySet()) {
      System.out.println(entry.getKey() + ": " + entry.getValue().size() + " employees");
    }
  }

  /**
   * Circular references for testing variable expansion.
   *
   * <p>
   * <b>Try this:</b>
   * <ul>
   * <li>Set breakpoint after node creation</li>
   * <li>Inspect {@code node1} variable</li>
   * <li>Expand {@code next} field to see {@code node2}</li>
   * <li>Expand {@code node2.next} to see {@code node3}</li>
   * <li>Notice {@code node3.next} points back to {@code node1} (circular!)</li>
   * <li>Check how debugger handles circular references</li>
   * </ul>
   */
  public void circularReferences() {
    Node node1 = new Node(1);
    Node node2 = new Node(2);
    Node node3 = new Node(3);

    // Create circular linked list
    node1.next = node2;
    node2.next = node3;
    node3.next = node1; // Circular reference!

    // Breakpoint here - expand to see circular reference
    System.out.println("Node 1 value: " + node1.value);
    System.out.println("Node 1 -> Node 2 -> Node 3 -> back to Node 1");
  }

  /**
   * Static field inspection.
   *
   * <p>
   * <b>Try this:</b>
   * <ul>
   * <li>Set breakpoint in this method</li>
   * <li>Use getStaticFields operation</li>
   * <li>Inspect {@code APP_NAME}, {@code MAX_USERS}, {@code userCount}</li>
   * <li>Notice static vs instance fields</li>
   * </ul>
   */
  public void staticFieldInspection() {
    userCount++; // Modify static field

    // Breakpoint here - inspect static fields
    System.out.println("App: " + APP_NAME);
    System.out.println("Max users: " + MAX_USERS);
    System.out.println("Current users: " + userCount);
  }

  /**
   * Array inspection including multi-dimensional arrays.
   *
   * <p>
   * <b>Try this:</b>
   * <ul>
   * <li>Set breakpoint after array creation</li>
   * <li>Inspect {@code numbers} array - expand to see elements</li>
   * <li>Inspect {@code matrix} - expand to see rows</li>
   * <li>Expand each row to see elements</li>
   * <li>Evaluate: {@code matrix[1][2]}</li>
   * </ul>
   */
  public void arrayInspection() {
    // Single-dimensional array
    int[] numbers = { 10, 20, 30, 40, 50 };

    // Multi-dimensional array
    int[][] matrix = { { 1, 2, 3 }, { 4, 5, 6 }, { 7, 8, 9 } };

    // Array of objects
    Person[] people = { new Person("Alice", 30, null), new Person("Bob", 25, null), new Person("Charlie", 35, null) };

    // Breakpoint here - inspect arrays
    System.out.println("Numbers length: " + numbers.length);
    System.out.println("Matrix dimensions: " + matrix.length + "x" + matrix[0].length);
    System.out.println("People count: " + people.length);
  }

  /**
   * Complex nested object with mixed types.
   *
   * <p>
   * <b>Try this:</b>
   * <ul>
   * <li>Set breakpoint after company creation</li>
   * <li>Inspect {@code company} variable</li>
   * <li>Expand departments (Map)</li>
   * <li>Expand each department's employees (List)</li>
   * <li>Expand each employee's address (Address)</li>
   * <li>Inspect metadata (Map with mixed types)</li>
   * </ul>
   */
  public void complexNestedObject() {
    Company company = new Company("TechCorp");

    // Add departments with employees
    City sf = new City("San Francisco", "CA", 873965);
    City nyc = new City("New York", "NY", 8336817);

    Person alice = new Person("Alice", 30, new Address("123 Main St", sf, "94102"));
    Person bob = new Person("Bob", 28, new Address("456 Broadway", nyc, "10013"));

    company.addEmployee("Engineering", alice);
    company.addEmployee("Engineering", bob);

    // Add metadata
    company.addMetadata("founded", 2020);
    company.addMetadata("public", false);
    company.addMetadata("revenue", 1_000_000.50);

    // Breakpoint here - explore complex structure
    System.out.println("Company: " + company.name);
    System.out.println("Departments: " + company.departments.keySet());
    System.out.println("Metadata: " + company.metadata);
  }

  /**
   * Run all data structure scenarios.
   */
  public void runAllScenarios() {
    System.out.println("\n=== Data Structure Scenarios ===\n");

    System.out.println("1. Object Hierarchy:");
    objectHierarchy();

    System.out.println("\n2. Collection Inspection:");
    collectionInspection();

    System.out.println("\n3. Nested Structures:");
    nestedStructures();

    System.out.println("\n4. Circular References:");
    circularReferences();

    System.out.println("\n5. Static Field Inspection:");
    staticFieldInspection();

    System.out.println("\n6. Array Inspection:");
    arrayInspection();

    System.out.println("\n7. Complex Nested Object:");
    complexNestedObject();

    System.out.println("\n=== Data Structure Scenarios Complete ===\n");
  }

  // ============================================================================
  // Supporting classes
  // ============================================================================

  /**
   * Person class with nested Address.
   */
  public static class Person {
    private final String name;
    private final int age;
    private final Address address;

    public Person(String name, int age, Address address) {
      this.name = name;
      this.age = age;
      this.address = address;
    }

    public String getName() {
      return name;
    }

    public int getAge() {
      return age;
    }

    public Address getAddress() {
      return address;
    }

    @Override
    public String toString() {
      return "Person{name='" + name + "', age=" + age + "}";
    }
  }

  /**
   * Address class with nested City.
   */
  public static class Address {
    private final String street;
    private final City city;
    private final String zipCode;

    public Address(String street, City city, String zipCode) {
      this.street = street;
      this.city = city;
      this.zipCode = zipCode;
    }

    public String getStreet() {
      return street;
    }

    public City getCity() {
      return city;
    }

    public String getZipCode() {
      return zipCode;
    }

    @Override
    public String toString() {
      return street + ", " + city.getName() + " " + zipCode;
    }
  }

  /**
   * City class.
   */
  public static class City {
    private final String name;
    private final String state;
    private final int population;

    public City(String name, String state, int population) {
      this.name = name;
      this.state = state;
      this.population = population;
    }

    public String getName() {
      return name;
    }

    public String getState() {
      return state;
    }

    public int getPopulation() {
      return population;
    }

    @Override
    public String toString() {
      return name + ", " + state + " (pop: " + population + ")";
    }
  }

  /**
   * Node class for circular reference demonstration.
   */
  public static class Node {
    private final int value;
    private Node next;

    public Node(int value) {
      this.value = value;
    }

    public int getValue() {
      return value;
    }

    public Node getNext() {
      return next;
    }
  }

  /**
   * Company class with complex nested structure.
   */
  public static class Company {
    private final String name;
    private final Map<String, List<Person>> departments;
    private final Map<String, Object> metadata;

    public Company(String name) {
      this.name = name;
      this.departments = new HashMap<>();
      this.metadata = new HashMap<>();
    }

    public void addEmployee(String department, Person person) {
      departments.computeIfAbsent(department, _ -> new ArrayList<>()).add(person);
    }

    public void addMetadata(String key, Object value) {
      metadata.put(key, value);
    }

    public String getName() {
      return name;
    }

    public Map<String, List<Person>> getDepartments() {
      return departments;
    }

    public Map<String, Object> getMetadata() {
      return metadata;
    }
  }
}
