# gemma-plugin

Gemma plugin for KIT DM 2.0 installations in order to prepare metadata for elastic search indexing. It works hand in hand with the messaging feature of the repository
which sends messages on a number of repository events, e.g. metadata creation, update, deletion and content upload. If the plugin receives a basic metadata event it
obtains the data resource metadata, transforms it according to the mapping stored for content type 'application/vnd.datamanager.data-resource+json' and uploads the result
to '{data_resource_id}/data/generated/{data_resource_id}_metadata.elastic.json'.

For content uploads the plugin checks for the media type of the uploaded file to obtain the mapping from the configured list of mappings (see below). If a mapping was found
it will be applied to the particular file and the result is uploaded to '{data_resource_id}/data/generated/{filename}.elastic.json'.

## How to build and install

In order to build and use this plugin you'll need:

* Java SE Development Kit 8 or higher
* Python 3+
* Gemma (https://git.scc.kit.edu/kitdatamanager/2.0/gemma)

After obtaining the sources change to the folder where the sources are located perform the following steps:

```
user@localhost:/home/user/gemma-plugin$ ./gradlew build
> Configure project :
<-------------> 0% EXECUTING [0s]
[...]
user@localhost:/home/user/gemma-plugin$
```

After building the plugin, you'll find a file named 'gemma-plugin.jar' at 'build/libs/'. This file has to be copied to 
your KIT DM 2.0 location into the 'lib' folder containing external libraries. Before starting the repository you should first 
check the configuration section as all properties mentioned over there are mandatory.

Now you can start your KIT DM 2.0 instance following the procedure decribed under [Enhanced Startup](https://git.scc.kit.edu/kitdatamanager/2.0/base-repo#enhanced-startup).
The plugin will be automatically detected and will be available after startup.

## Configuration

The plugin can be configured using the standard KIT DM 2.0 configuration file 'application.properties'. Supported properties are: 

| Property Key| Description | Default Value |
| ------ | ------ | ------ |
| repo.plugin.repositoryBaseUrl | Base URL for querying repository resources (used for all plugins) | http://localhost:8090/api/v1/dataresources/ |
| repo.plugin.gemma.pythonLocation | Absolute path to the local Python executable (NOT only the installation folder!) | none |
| repo.plugin.gemma.gemmaLocation | Absolute path to the Gemma main script, e.g. /home/user/gemma/mapping_single.py | none |
| repo.plugin.gemma.mappingsLocation | Absolute path to the folder containing all Gemma transformation mapping files. | none |
| repo.plugin.gemma.schemaMappings | Key-Value list of mappings, where the key is the content type and the value is the name of a file located in 'mappingsLocation', containing the mapping for the particular content type. | none |

In order to configure the plugin properly, you should have installed Python 3+ and you should have cloned [Gemma](https://git.scc.kit.edu/kitdatamanager/2.0/gemma) into
a local folder. Afterwards, the properties 'pythonLocation' and 'gemmaLocation' can be provided.

Finally, a folder containing all mapping files has to be created, e.g. where the Gemma repository was cloned to. This folder has to be provided as 'mappingsLocation'
and will contain all mapping files. 

### First Sample Mapping

Let's assume we want to create a mapping of the standard data resource metadata every repository resource has. First, we create a file 'simple_mapping.json' at the folder
'mappingsLocation' we've just created. Then add the following mapping to the file: 

```
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "$id": "http://example.com/product.schema.json",
  "title": "Simple Mapping",
  "description": "Data resource mapping from json",
  "type": "object",
  "properties":{
  "Publisher":{
   "path": "publisher",
   "type": "string"
   },
   "Publication Date":{
   "path": "publicationDate",
   "type": "string"
   }
  }
}
```

Of course, this mapping does not make much sense but it's the first mapping we can use. The result will look as follows: 

```
{
    "Publisher":"The publisher",
    "PublicatioDate":"2019"
}
```

In order to make it available to the gemma-plugin, it has to be registered as valid mapping for the data resource content type. 
Thus, the according property inside 'application.properties' should look as follows:

```
[...]
repo.plugin.gemma.schemaMappings[application/vnd.datamanager.data-resource+json]:simple_mapping.json
[...]
```

The configuration entry maps the content type 'application/vnd.datamanager.data-resource+json' to the mapping file 'simple_mapping.json' 
which has to be present at the 'mappingsLocation' folder.

## License

The KIT Data Manager is licensed under the Apache License, Version 2.0.


