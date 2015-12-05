(ns om-tutorial.D-Queries
  (:require-macros
    [cljs.test :refer [is]]
    )
  (:require [om.next :as om :refer-macros [defui]]
            [om.next.impl.parser :as p]
            [om-tutorial.queries.query-editing :as qe]
            [om.dom :as dom]
            [cljs.reader :as r]
            [om-tutorial.queries.query-demo :as qd]
            [devcards.util.edn-renderer :refer [html-edn]]
            [devcards.core :as dc :refer-macros [defcard defcard-doc]]
            [cljsjs.codemirror]
            [cljsjs.codemirror.mode.clojure]
            [cljsjs.codemirror.addons.matchbrackets]
            [cljsjs.codemirror.addons.closebrackets]
            ))

(defcard-doc
  "
  # Om Queries

  ## Important Notes

  First, please understand that *Om does not know how to read your data* on the client *or* the server.
  It does provide some useful utilities for the default database format described in the App
  Database section, but since you can structure things in that format in some arbitrary way
  you do have to participate in query result construction. Make sure you've read the App
  Database section carefully, as we'll be leveraging that understanding here.

  That said, let's understand the query syntax and semantics.

  ## Query Syntax

  Queries are written with a variant of Datomic pull syntax.

  For reference, here are the defined grammar elements:
  ```clj
  [:some/key]                              ;;prop
  [(:some/key {:arg :foo})]                ;;prop + params
  [{:some/key [:sub/key]}]                 ;;join + sub-select
  [:some/k1 :some/k2 {:some/k3 ...}]       ;;recursive join
  [({:some/key [:sub/key]} {:arg :foo})]   ;;join + params
  [[:foo/by-id 0]]                         ;;reference
  [(fire-missiles!)]                       ;;mutation
  [(fire-missiles! {:target :foo})]        ;;mutation + params
  { :photo [...subquery...]
    :video [...subquery...]
    :comment [...subquery...] }             ;;union
  ```

  *RECOMMENDATION*: Even if you do not plan to use Datomic, I highly recommend
  going through the [Datomic Pull Tutorial](http://docs.datomic.com/pull.html).
  It will really help you with Om Next queries.

  ## A quick note on Quoting

  Quoting is not an Om thing, it is a clj(s) thing. The syntax of Om queries is just data, but it
  uses things (like symbols and lists) that the compiler would like to give different meaning to than
  we need.

  If you have not done much in the way of macro programming you may be confused by the
  quoting often seen in these queries. I'd recommend doing a tutorial on macros, but
  here are some quick notes:

  - Using `'` quotes the form that follows it, making it literal.
  - Using a backquote in front of a form is a syntax quote. It will namespace all symbols, and allows
    you to unquote subforms using `~`. You'll sometimes also see ~' in front of a non-namespaced
    symbol *within* a syntax quoted form. This prevents a namespace from being added (unquote a form
    that literally quotes the symbol).

  ## Understanding Queries

  Except for unions, queries are represented as vectors. Each item in the vector is a request for a data item, or
  is a call to an abstract operation. Data items can indicate [joins](/#!/om_tutorial.Z_Glossary) by
  nesting the given property name in a map with exactly one key:

  ```
  [:a :b] ; Query for properties :a and :b
  [:a {:b [:c]}] ; Query for property :a, and then follow property :b to an object (map) and include property :c from it
  ```

  The join key indicates that a keyword of that name exists on the data being queried,
  and the value is either a map (for a to-one relation) or a vector (to-many).

  The result for the above queries would be maps, keyed by the query selectors:

  ```
  {:a 1 :b 2} ; possible result for the first
  {:a 1 :b { :c 3} } ; possible result for the second
  ```

  So, let's start with a very simple database and play with some queries in the card below:"
  )

(defcard query-example-1
         "This query starts out as one that asks for a person's name from the database. You can see our database (:db in the map below)
         has a bunch of top level properties...the entire database is just a single person.
         Play with the query. Ask for this person's age and database ID.

         Notes:

         - The query has to be a single vector
         - The result is a map, with keys that match the selectors in the query.
         "
         qe/query-editor
         {:query        "[:person/name]"
          :query-result {}
          :db           {:db/id 1 :person/name "Sam" :person/age 23}
          :id           "query-example-1"}
         {:inspect-data false})

(defcard-doc
  "
  A more interesting database has some tables in it, like we saw in the App Database section. Let's play with
  queries on one of those.")

(defcard query-example-2
         "This database (in :db below) has some performance statistics linked into a table and chart. Note that
         the query for the table is for the disk data, while the chart is combining multiple bits of data. Play with the query a bit
         to make sure you understand it (e.g. erase it and try to write it from scratch).

         Again note that the query result is a map in tree form. A tree is exactly what you need for a UI!

         Some interesting queries to try:

         - `[:table]`
         - `[{:chart [{:data [:cpu-usage]}]}]`
         - `[ [:statistics :performance] ]`
         - `[{[:statistics :performance] [:disk-activity]}]`
         "
         qe/query-editor
         {:query        "[{:table [:name {:data [:disk-activity]}]}   {:chart [:name {:data [:disk-activity :cpu-usage]}]}]"
          :query-result {}
          :db           {:table      {:name "Disk Performance Table" :data [:statistics :performance]}
                         :chart      {:name "Combined Graph" :data [:statistics :performance]}
                         :statistics {:performance {
                                                    :cpu-usage        [45 15 32 11 66 44]
                                                    :disk-activity    [11 34 66 12 99 100]
                                                    :network-activity [55 87 20 01 22 82]}}}
          :id           "query-example-2"}
         {:inspect-data false})

(defcard-doc "

  A large percentage of your queries will fall into the property or join variety, so we'll leave the
  rest of the grammar for now, and move on to how these queries work with the UI.

  ## Co-located Queries on Components

  OK, now things start to get really cool, because now that you understand the basic UI, queries, and the app
  database format, we're ready to combine them. After all, our goal is to make a webapp isn't it?

  ### The Problem

  So, we've seen great ways to lay out our data into these nice graph databases, and we've seen how
  to query them. Two questions remain:

  1. What's the easiest way to get my data into one of these databases?
  2. How does this relate to my overall UI?

  ### The Solution

  These two questions are tightly related. David Nolen had this wonderful realization that when
  rendering the tree of your UI you are doing the same thing as when you're evaluating a graph
  query.

  Both the *UI render* and *running the query* are \"walks\" of a graph of data. Furthermore,
  if the UI tree participates in defining which bits of the query have distinct identity,
  then you can use *that* information to walk both (a sample tree and the current UI query) at
  the same time and take the process in reverse (from a *sample* UI tree into the desired graph
  database format)!

  ## Details

  So, let's see how that works.

  Placing a query on a component declares what data that component needs in order to render correctly.
  Components are little fragments of UI, so the queries on them are little fragments of the query.
  Thus, a `Person` component might declare it needs a person's name:

  "
             (dc/mkdn-pprint-source qd/Person)
             "

             but you have yet to answer \"which one\" by placing it somewhere in a join.

             Examples might be:

             Get people that are my friends (e.g. relative to your login cookie):

             ```
             [{:current-user/friends (om/get-query Person)}]
             ```

             Get people that work for the company (context of login):

             ```
             [{:company/active-employees (om/get-query Person)}]
             ```

             Get a very specific user (using an ident):

             ```
             [{[:user/by-id 42] (om/get-query Person)}]
             ```

             The `query-demo.cljs` contains components:

             "
             (dc/mkdn-pprint-source qd/Person)
             (dc/mkdn-pprint-source qd/person)
             (dc/mkdn-pprint-source qd/PeopleWidget)
             (dc/mkdn-pprint-source qd/people-list)
             (dc/mkdn-pprint-source qd/Root)
             "

             The above component make the following UI tree:

             TODO: UI Tree diagram

             and the queries form the following query tree:

             TODO: Query diagram

             because the middle component (PeopleWidget) does not have a query. Pay careful attention to how the queries are
             composed (among stateful components). It is perfectly fine for a UI component to not participate in the query,
             in which case you must remember that the walk of render will not match the walk of the result data. This
             is shown in the above code: note how the root element asks for `:people`, and the render of the root
             pulls out that data and passes it down. Then note how `PeopleWidget` pulls out the entire properties
             and passes them on to the component that asked for those details. In fact, you can change `PeopleWidget`
             into a simple function. There is no specific need for it to be a component, as it is really just doing
             what the root element needed to do: pass the items from the list of people it queried into the
             final UI children (`Person`) that asked for the details found in each of the items. The middle widget isn't
             participating in the state tree generation, it is merely an artifact of rendering.

             So, this example will render correctly when the query result looks like what you see in the card below:
           ")

(defcard sample-rendering-with-result-data
         (fn [state _] (qd/root @state))
         {:people [{:db/id 1 :person/name "Joe"}
                   {:db/id 2 :person/name "Guy"}
                   {:db/id 3 :person/name "Tammy"}
                   ]}
         {:inspect-data true}
         )

(defcard-doc "

  ## More Advanced Queries

  ### Parameters

  All of the query elements can be parameterized. These parameters are passed down to the query engine (which you help
  write), and can be used however you choose. Om does not give any meaning whatsoever to these parameters. See
  the section on [State Reads and Parsing](#!/om_tutorial.E_State_Reads_and_Parsing) for more information.

  ### Looking up by Ident

  An Ident is a vector with 2 elements. The first is a keyword and the second is some kind of
  value (e.g. keyword, numeric id, etc.). These are the same idents you saw in the App Database section,
  and can be used in place of a property name to indicate a specific instance
  of some object in the database (as property access or a join). This provides explicit context from
  which the remainder of the query can be evaluated.

  ### Union Queries

  When a component is showing a sequence of things and each of those things might be different, then you need
  a union query. Basically, it is a *join*, but it names all of the alternative things that might appear
  in the resulting collection. Instead of being a vector, unions are maps of vectors (where each value in the map
  is the query for the keyed kind of thing). They look like multiple joins all merged into a single map.
  ")

(defcard union-queries
         "This database (in :db below)

          Some interesting queries to try:

          - `[{:panels {:panelC [:sticky], :panelA [:boo], :panelB [:goo]}}]` (access a list)
          - `[{:current-panel {:panelC [:sticky], :panelA [:boo], :panelB [:goo]}}]`  (access a singleton)
          - `[{[:panelA 1] {:panelC [:sticky], :panelA [:boo], :panelB [:goo]}}]`  (access a singleton by ident)
         "
         qe/query-editor
         {:query        "[{:panels {:panelA [:boo] :panelB [:goo] :panelC [:sticky]}}]"
          :query-result {}
          :db           {
                         :panels [[:panelA 1] [:panelB 1][:panelC 1]]
                         :panelA { 1 {:boo 42}}
                         :panelB { 1 {:goo 8}}
                         :panelC { 1 {:sticky true}}
                         :current-panel [:panelA 1]
                         }
          :id           "union-queries"}
         {:inspect-data false})

(defcard-doc "
  ## Common Mistakes

  ### Failing to Reach the UI Root

  Om only looks for the query on the root component of your UI! Make sure your queries compose all the way to
  the root! Basically the Root component ends up with one big fat query for the whole UI, but you get to
  *reason* about it through composition (recursive use of `get-query`). Also note that all of the data
  gets passed into the Root component, and every level of the UI that asked for (or composed in) data
  must pick that apart and pass it down. In other words, you can pretend like you UI doesn't even have
  queries when working on your render functions. E.g. you can build your UI, pick apart a pretend
  result, then later add queries and everything should work.

  ### Declaring a query that is not your own

  Beginners often make the mistake:

  ```
  (defui Widget
       static om/IQuery
       (query [this] (om/get-query OtherWidget))
       ...)
  ```

  because they think \"this component just needs what the child needs\". If that is truly the case, then
  Widget should not have a query at all (the parent should compose OtherWidget's into it's own query). The most common
  location where this happens is at the root, where you may not want any specific data yourself.

  In that case, you *do* need a stateful component at the root, but you'll need to get the child data
  using a join, and then pick it apart via code and manually pass those props down:

  ```
  (defui RootWidget
       static om/IQuery
       (query [this] [{:other (om/get-query OtherWidget)}])
       Object
       (render [this]
          (let [{:keys [other]} (om/props this)] (other-element other)))
  ```

  ### Making a component when a function would do

  Sometimes you're just trying to clean up code and factor bits out. Don't feel like you have to wrap UI code in
  `defui` if it doesn't need any support from React or Om. Just write a function! `PeopleWidget` earlier in this
  document is a great example of this.
  ")
